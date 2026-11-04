"""Against a real PostgreSQL (with pgvector available). Run: AI_TEST_DATABASE_URL=... pytest -m integration

The database is a throwaway one (e.g. dsports_ai_test): the tests drop the AI-owned tables first.
"""
import os
import uuid

import asyncpg
import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.obs.usage import UsageRecorder
from app.store.db import Store
from tests.conftest import make_settings

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]

OWNED_TABLES = ("chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta")


@pytest.fixture
async def store():
    conn = await asyncpg.connect(DSN)
    for table in OWNED_TABLES:
        await conn.execute(f"DROP TABLE IF EXISTS {table} CASCADE")
    await conn.close()
    s = await Store.connect(DSN, 10)
    yield s
    await s.close()


async def fetch(query, *args):
    conn = await asyncpg.connect(DSN)
    try:
        return await conn.fetch(query, *args)
    finally:
        await conn.close()


async def test_schema_is_created_and_idempotent(store):
    await store.ensure_schema()
    await store.ensure_schema()
    tables = {r["tablename"] for r in await fetch("SELECT tablename FROM pg_tables WHERE schemaname='public'")}
    assert {"ai_meta", "conversations", "audit", "usage", "pending_actions", "product_index", "documents", "chunks"} <= tables
    assert tables.isdisjoint({"products", "orders", "payments", "prices", "inventory", "customers", "order_items"}), "no commerce TABLES in the AI db"
    assert {"inv_sku", "inv_demand_daily", "inv_assessment", "inv_audit"} <= tables, "the Phase 2 analytical mart is created too"
    version = await fetch("SELECT value FROM ai_meta WHERE key='schema_version'")
    assert version[0]["value"] == "1"


async def test_the_ai_database_holds_no_commerce_data(store):
    """Customer AI (Phase 1) tables: derived retrieval data only, nothing authoritative about price, stock, orders, payments or customers.
    Phase 2 deliberately adds an analytical mart (`inv_*`: a catalogue price snapshot and POC stock positions for planning). It must still hold NO
    customer, order or payment identifiers; the rules differ, so each is checked on its own tables."""
    columns = {(r["table_name"], r["column_name"]) for r in await fetch(
        "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema='public'")}
    phase1 = {c for t, c in columns if not t.startswith(("inv_", "src_", "pln_"))}
    assert not phase1 & {"price", "selling_price", "mrp", "stock", "available_quantity", "email", "phone", "address", "order_id", "payment_id", "user_id"}
    # Phase 5 (Product & Supplier Intelligence, `src_*`): buyer planning data about products DSports does not sell yet. No customer, order, payment or stock identifiers, no live price or
    # stock; the only price-like column is the buyer's PLANNED selling price on the opportunity (never read by checkout) and supplier quote prices.
    sourcing = {(t, c) for t, c in columns if t.startswith("src_")}
    assert not {c for _, c in sourcing} & {"email", "phone", "address", "order_id", "payment_id", "user_id", "customer_id", "order_number", "password", "first_name", "last_name", "mrp", "stock", "available_quantity"}
    assert {t for t, c in sourcing if c == "selling_price"} <= {"src_opportunities"}
    # Phase 6 (Sales & Inventory Planning, `pln_*`): derived sales aggregates keyed by SKU and store. The only commerce reference is the source order id and number on a sales line (needed to restate a week on a late return);
    # no customer, payment, contact or address columns and no live price or stock.
    planning = {(t, c) for t, c in columns if t.startswith("pln_")}
    assert not {c for _, c in planning} & {"email", "phone", "address", "payment_id", "user_id", "customer_id", "password", "first_name", "last_name", "mrp", "stock", "available_quantity"}
    assert {t for t, c in planning if c in ("order_id", "order_number")} <= {"pln_sales_line"}
    assert {"product_id", "embedding", "content_hash"} <= phase1
    inventory = {c for t, c in columns if t.startswith("inv_")}
    assert not inventory & {"email", "phone", "address", "order_id", "payment_id", "user_id", "customer_id", "order_number", "password", "first_name", "last_name"}
    assert {"data_source"} <= inventory, "every analytical row is labelled"


async def test_pgvector_extension_is_available_to_the_database():
    available = await fetch("SELECT default_version FROM pg_available_extensions WHERE name='vector'")
    assert available and available[0]["default_version"]


async def test_embedding_config_change_is_detected_and_not_silently_overwritten(store):
    assert await store.check_embedding_config("text-embedding-005", 768) == "initialized"
    assert await store.check_embedding_config("text-embedding-005", 768) == "ok"
    assert await store.check_embedding_config("gemini-embedding-001", 3072) == "reindex_required"
    assert await store.check_embedding_config("text-embedding-005", 256) == "reindex_required"
    # the recorded config is unchanged until a re-index completes
    assert await store.check_embedding_config("text-embedding-005", 768) == "ok"


async def test_usage_is_persisted_with_estimated_cost(store):
    recorder = UsageRecorder({"m": {"input_per_1m": 1.0, "output_per_1m": 2.0}}, store)
    await recorder.record(operation="generate", model="m", tokens_in=1000, tokens_out=500, latency_ms=321,
                          tier="reasoning", intent="product_search", customer_hash="hash-1")
    await recorder.record(operation="embed", model="unpriced", tokens_in=None, tokens_out=None, latency_ms=12)
    rows = await fetch("SELECT * FROM usage ORDER BY id")
    assert len(rows) == 2
    first, second = rows
    assert (first["model"], first["tokens_in"], first["tokens_out"], first["latency_ms"]) == ("m", 1000, 500, 321)
    assert float(first["cost_estimate"]) == pytest.approx(0.002)
    assert first["cost_is_estimate"] is True and first["cost_currency"] == "USD"
    assert (first["tier"], first["intent"], first["customer_hash"]) == ("reasoning", "product_search", "hash-1")
    assert second["cost_estimate"] is None  # unpriced model => no invented cost


async def test_conversations_are_scoped_to_their_owner_hash(store):
    mine, theirs = uuid.uuid4(), uuid.uuid4()
    conn = await asyncpg.connect(DSN)
    try:
        await conn.execute("INSERT INTO conversations (id, customer_hash) VALUES ($1, 'owner-a'), ($2, 'owner-b')", mine, theirs)
        visible = await conn.fetch("SELECT id FROM conversations WHERE id=$1 AND customer_hash=$2", theirs, "owner-a")
        assert visible == []  # the owner predicate is what future code must always apply
        assert len(await conn.fetch("SELECT id FROM conversations WHERE id=$1 AND customer_hash=$2", mine, "owner-a")) == 1
    finally:
        await conn.close()


async def test_audit_table_accepts_a_write_proposal(store):
    conn = await asyncpg.connect(DSN)
    try:
        await conn.execute("INSERT INTO audit (customer_hash, correlation_id, action, tool, args_hash, outcome) "
                           "VALUES ('h', 'c-1', 'PROPOSED', 'create_return', 'abc', 'PENDING_CONFIRMATION')")
        assert (await conn.fetchval("SELECT count(*) FROM audit")) == 1
    finally:
        await conn.close()


def test_application_connects_creates_schema_and_reports_database_up(store):
    settings = make_settings(AI_DATABASE_URL=DSN)
    with TestClient(create_app(settings)) as client:
        body = client.get("/health/dependencies").json()
        assert body["dependencies"]["database"]["status"] == "UP"
        assert body["dependencies"]["database"]["embedding_config"] in ("initialized", "ok")
        assert DSN not in str(body)
