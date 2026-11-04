"""Inventory Intelligence is decision support: asking it to act must change NOTHING. This test does not trust refusal text. It runs requests to change prices,
create purchase orders, transfer stock and launch promotions (plus ordinary questions) through the real copilot against a real PostgreSQL mart, then asserts
  - every tool the registry actually executed is a registered READ tool,
  - a fingerprint of every protected mart table is byte-for-byte unchanged,
  - the audit trail records the questions and no state-changing action,
  - no model was retrained by a question.
"""
import hashlib
import os
import uuid
from datetime import date

import pytest

from app.inventory import generator as G
from app.inventory import pipeline
from app.inventory.copilot import AskRequest, InventoryCopilot
from app.services import build_services
from app.store.db import Store
from app.tools.registry import Kind
from tests.conftest import make_settings, make_token
from tests.fakes import FakeLLM
from tests.integration.test_inventory_db import INV_TABLES, skus
from app.auth.jwt import verify_token

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
PROTECTED = ("inv_sku", "inv_position", "inv_purchase_order", "inv_event", "inv_demand_daily", "inv_stock_snapshot", "inv_forecast", "inv_assessment",
             "inv_elasticity", "inv_event_impact", "inv_region_share", "inv_model_run")
ADMIN = verify_token(make_token(str(uuid.uuid4()), roles=("ADMIN",)), make_settings())
CUSTOMER = verify_token(make_token(str(uuid.uuid4()), roles=("CUSTOMER",)), make_settings())

WRITE_REQUESTS = [
    "Create a purchase order for 100 cricket bats",
    "Change the price of the MRF cricket bat to 5000",
    "Launch a 30% promotion on all jerseys now",
    "Transfer 200 units of stock to the East warehouse",
    "Please reduce the price of every jersey by 10%",
    "Can you raise a PO for 200 running shoes?",
    "Go ahead and apply a 50% discount to all cricket products",
    "Approve the clearance plan and update the prices",
]
READ_QUESTIONS = ["Which products are at risk of stock-out?", "What happens if we give 30% discount?", "Which products should go on clearance?",
                  "Do we have enough cricket inventory for the tournament?", "Where should we move excess inventory?"]


async def snapshot(pool) -> dict[str, str]:
    out = {}
    for t in PROTECTED:
        rows = await pool.fetch(f"SELECT t::text AS r FROM {t} t ORDER BY 1")
        out[t] = hashlib.sha256("\n".join(r["r"] for r in rows).encode()).hexdigest()
    return out


@pytest.fixture
async def rig():
    import asyncpg
    conn = await asyncpg.connect(DSN)
    for t in (*INV_TABLES, "chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    await pipeline.seed_skus(store.pool, skus(), date(2026, 10, 8))
    await pipeline.train(store.pool)
    llm = FakeLLM(default="Nothing was executed; this is a recommendation only.")
    services = await build_services(make_settings(), store=store, llm_inner=llm)
    calls: list[str] = []
    original = services.registry.call

    async def spy(name, args, ctx):
        calls.append(name)
        return await original(name, args, ctx)

    services.registry.call = spy                     # records every tool the copilot really executes
    yield store, services, InventoryCopilot(services), calls
    await services.aclose()


async def test_requests_to_act_execute_no_tool_and_change_no_state(rig):
    store, services, copilot, calls = rig
    before = await snapshot(store.pool)
    for q in WRITE_REQUESTS:
        r = await copilot.ask(ADMIN, AskRequest(message=q))
        assert r.status == "REFUSED" and r.tools == [], q
    assert calls == []                                                  # no tool was executed AT ALL for any write request
    assert await snapshot(store.pool) == before
    audit = await store.pool.fetch("SELECT detail->>'status' AS s, detail->'tools' AS t FROM inv_audit WHERE action='QUERY'")
    assert len(audit) == len(WRITE_REQUESTS) and all(a["s"] == "REFUSED" and a["t"] == "[]" for a in audit)


async def test_ordinary_questions_use_only_read_tools_and_leave_the_mart_untouched(rig):
    store, services, copilot, calls = rig
    runs_before = await store.pool.fetchval("SELECT count(*) FROM inv_model_run")
    before = await snapshot(store.pool)
    for q in READ_QUESTIONS + WRITE_REQUESTS:
        await copilot.ask(ADMIN, AskRequest(message=q))
    read_tools = set(services.registry.names(Kind.READ))
    assert calls and set(calls) <= read_tools                           # every executed tool is a registered READ tool
    assert not {"create_return", "reorder_product"} & set(calls)       # in particular none of the customer WRITE tools
    assert await snapshot(store.pool) == before                         # includes inv_model_run: answering a question never retrains
    assert await store.pool.fetchval("SELECT count(*) FROM inv_model_run") == runs_before
    # the only thing that grows is the audit trail and the scenario log (calculations, not actions)
    actions = {r["action"] for r in await store.pool.fetch("SELECT DISTINCT action FROM inv_audit")}
    assert actions <= {"QUERY", "CLEARANCE", "TRAIN"}


async def test_a_customer_session_cannot_run_any_inventory_tool_even_when_called_directly(rig):
    store, services, copilot, calls = rig
    from app.tools.registry import ToolContext
    ctx = ToolContext(principal=CUSTOMER, dsports=services.dsports, settings=services.settings, services=services, max_calls=8)
    before = await snapshot(store.pool)
    for tool in [t for t in services.registry.all() if t.required_role]:
        r = await services.registry.call(tool.name, {}, ctx)
        assert not r.ok and r.error_code in ("FORBIDDEN", "INVALID_ARGUMENTS"), tool.name
    with pytest.raises(PermissionError):
        await copilot.ask(CUSTOMER, AskRequest(message="Which products are at risk of stock-out?"))
    assert await snapshot(store.pool) == before


async def test_the_scenario_calculation_never_touches_prices_or_costs(rig):
    store, services, copilot, calls = rig
    before = await snapshot(store.pool)
    for q in ("What happens if we give 30% discount?", "What happens if we give 50% discount?", "Which scenario is better?"):
        await copilot.ask(ADMIN, AskRequest(message=q))
    assert await snapshot(store.pool) == before
    assert await store.pool.fetchval("SELECT count(*) FROM inv_scenario_run") >= 3        # logged as calculations
