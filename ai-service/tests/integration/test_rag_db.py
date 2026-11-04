"""Indexing, ingestion, hybrid retrieval and state against a REAL PostgreSQL with pgvector.

Embeddings here are a deterministic bag-of-words hash (so the tests are repeatable and free): they make the vector columns, HNSW
index, cosine distance, full-text search and RRF fusion real, but they say nothing about Gemini's quality. The real Tika + OCR +
Gemini + pgvector pipeline is exercised by tests/live/test_stack_rag.py.
"""
import asyncio
import hashlib
import math
import os
import re
import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.llm.base import EmbeddingResult, LLMError
from app.rag.embedder import Embedder
from app.rag.ingest import DocSpec, Ingestor
from app.rag.products import ProductIndexer, compose_search_text
from app.rag.reindex import reindex
from app.rag.retrieval import Retriever
from app.rag.tika import Extraction, html_to_pages
from app.shop.catalog import Catalog
from app.store.db import Store
from app.store.state import ConversationState, GUEST, PendingAction, PgStateStore
from app.tools.dsports_client import DSportsClient
from tests.conftest import make_settings
from tests.fakes import DEFAULT_PRODUCTS, FakeDSports

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
TABLES = ("chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta")


class BowClient:
    """Bag-of-words hashing embedder: texts sharing words get high cosine similarity."""

    def __init__(self, dim=768, fail=False):
        self.dim, self.fail, self.calls = dim, fail, 0

    async def embed(self, texts, *, task_type, customer_hash=None):
        if self.fail:
            raise LLMError("LLM_ERROR", "embedding down")
        self.calls += 1
        out = []
        for t in texts:
            v = [0.0] * self.dim
            for w in re.findall(r"[a-z0-9]+", t.lower()):
                v[int(hashlib.md5(w.encode()).hexdigest(), 16) % self.dim] += 1.0
            norm = math.sqrt(sum(x * x for x in v)) or 1.0
            out.append([x / norm for x in v])
        return EmbeddingResult(out, "bow-test", self.dim, 1)


class FakeTika:
    """Returns the text it is given (UTF-8 bytes) as one page, or pages split on a form feed; flags a 'scan' marker."""

    def __init__(self):
        self.calls = 0

    async def extract(self, data: bytes, content_type=None):
        self.calls += 1
        text = data.decode("utf-8")
        pages = text.split("\f")
        return Extraction(pages=pages, ocr_used=text.startswith("SCAN:"), ocr_pages=len(pages) if text.startswith("SCAN:") else 0)


@pytest.fixture
async def env():
    import asyncpg
    conn = await asyncpg.connect(DSN)
    for table in TABLES:
        await conn.execute(f"DROP TABLE IF EXISTS {table} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 10, 768)
    client = BowClient()
    embedder = Embedder(client, "bow-test", 768, cache_ttl_s=0)
    yield store, client, embedder
    await store.close()


def settings():
    return make_settings(AI_CHUNK_TOKENS=120, AI_CHUNK_OVERLAP_TOKENS=20)


# ---------------------------------------------------------------- product index + hybrid product search

async def indexed_products(store, embedder):
    dsports = FakeDSports()
    catalog = Catalog(DSportsClient(dsports.client()))
    indexer = ProductIndexer(store, catalog, embedder)
    return dsports, catalog, indexer, await indexer.index_all()


async def test_product_index_holds_descriptive_facts_and_is_idempotent(env):
    store, client, embedder = env
    dsports, catalog, indexer, first = await indexed_products(store, embedder)
    assert (first.total, first.indexed, first.unchanged) == (len(DEFAULT_PRODUCTS), len(DEFAULT_PRODUCTS), 0)
    row = await store.pool.fetchrow("SELECT * FROM product_index WHERE sku='CR-BAT-MRF-001'")
    assert row["name"] == "MRF Cricket Bat - Virgin" and row["sport"] == "Cricket" and row["weight_g"] == 1200 and row["embedding_model"] == "bow-test"
    assert "beginner" in row["search_text"] and "6499" not in row["search_text"] and "price" not in row["search_text"].lower()
    calls = client.calls
    second = await indexer.index_all()
    assert (second.indexed, second.unchanged) == (0, len(DEFAULT_PRODUCTS)) and client.calls == calls          # nothing re-embedded
    dsports.products[0]["desc"] = "A completely new description."
    third = await indexer.index_all()
    assert third.indexed == 1


async def test_removed_products_leave_the_index(env):
    store, _, embedder = env
    dsports, catalog, indexer, _ = await indexed_products(store, embedder)
    dsports.products.pop()
    summary = await indexer.index_all()
    assert summary.removed == 1 and await store.pool.fetchval("SELECT count(*) FROM product_index") == len(DEFAULT_PRODUCTS) - 1


async def test_hybrid_product_search_with_metadata_filters(env):
    store, _, embedder = env
    await indexed_products(store, embedder)
    retriever = Retriever(store, embedder, 20, 0.0)
    r = await retriever.search_products("lightweight cricket bat for a beginner", sport="Cricket", category="Bat", limit=3)
    assert [h.payload["name"] for h in r.hits][0] == "MRF Cricket Bat - Virgin"
    assert all(h.payload["category"] == "Bat" and h.payload["sport"] == "Cricket" for h in r.hits)
    assert r.hits[0].keyword_rank is not None and r.hits[0].vector_rank is not None and r.hits[0].similarity > 0.3
    assert len(r.hits) == 3 and r.latency_ms >= 0
    none = await retriever.search_products("bat", sport="Football")
    assert none.hits == []


async def test_search_degrades_to_keyword_only_when_embeddings_are_down(env):
    store, client, embedder = env
    await indexed_products(store, embedder)
    client.fail = True
    r = await Retriever(store, embedder, 20, 0.0).search_products("cricket bat", sport="Cricket")
    assert r.hits and all(h.vector_rank is None and h.similarity is None for h in r.hits) and r.hits[0].keyword_rank == 1


# ---------------------------------------------------------------- ingestion + chunk retrieval

GUIDE = ("# Bat care\n\nKnock in a new bat with a mallet for several hours. " * 1 + "Oil it lightly.\n\n## Storage\n\nStore the bat dry and away from heat.\n")
POLICY = "# Return policy\n\nA delivered product can be returned within 7 days of delivery.\n\n## Refunds\n\nRefunds are separate from returns.\n"


async def ingest(store, embedder, tika=None):
    return Ingestor(store, tika or FakeTika(), embedder, settings())


async def test_document_ingest_stores_chunks_with_metadata_and_is_idempotent(env):
    store, client, embedder = env
    ing = await ingest(store, embedder)
    spec = DocSpec(file="care.md", source="care.md", document_type="care", title="Bat Care", sport="Cricket", category="Bat", version="3")
    first = await ing.ingest(spec, GUIDE.encode())
    assert first.status == "ingested" and first.chunks >= 1 and first.embedded == first.chunks and first.ocr_used is False
    row = await store.pool.fetchrow("SELECT * FROM chunks ORDER BY chunk_index LIMIT 1")
    assert (row["source"], row["document_type"], row["sport"], row["category"], row["version"], row["page"], row["language"]) == \
        ("care.md", "care", "Cricket", "Bat", "3", 1, "en")
    assert row["content_hash"] and row["chunk_index"] == 0 and row["section"] == "Bat care"
    calls = client.calls
    again = await ing.ingest(spec, GUIDE.encode())
    assert again.status == "unchanged" and client.calls == calls


async def test_changed_document_reuses_embeddings_of_unchanged_chunks(env):
    store, client, embedder = env
    ing = await ingest(store, embedder)
    long_doc = "\n\n".join(f"# Part {i}\n\n" + (f"Paragraph about topic {i}. " * 30) for i in range(4))
    spec = DocSpec(file="long.md", source="long.md", document_type="guide")
    first = await ing.ingest(spec, long_doc.encode())
    assert first.chunks >= 3
    edited = long_doc.replace("topic 3", "topic three revised")
    second = await ing.ingest(spec, edited.encode())
    assert second.status == "ingested" and second.reused >= 1 and second.embedded < second.chunks
    assert await store.pool.fetchval("SELECT count(*) FROM chunks WHERE source='long.md'") == second.chunks


async def test_pages_keep_their_page_numbers_and_ocr_is_recorded(env):
    store, _, embedder = env
    ing = await ingest(store, embedder)
    r = await ing.ingest(DocSpec(file="scan.pdf", source="scan.pdf", document_type="warranty"), b"SCAN:Page one text about warranty.\fPage two text about claims.")
    assert r.pages == 2 and r.ocr_used is True
    pages = [row["page"] for row in await store.pool.fetch("SELECT page FROM chunks WHERE source='scan.pdf' ORDER BY page")]
    assert pages == [1, 2] and await store.pool.fetchval("SELECT ocr_used FROM documents WHERE source='scan.pdf'") is True


async def test_empty_document_is_rejected_and_removal_cascades(env):
    store, _, embedder = env
    ing = await ingest(store, embedder)
    with pytest.raises(ValueError):
        await ing.ingest(DocSpec(file="e.md", source="e.md", document_type="faq"), b"   ")
    await ing.ingest(DocSpec(file="p.md", source="p.md", document_type="policy"), POLICY.encode())
    assert await ing.remove("p.md") and await store.pool.fetchval("SELECT count(*) FROM chunks") == 0


async def test_hybrid_chunk_retrieval_filters_ranks_and_applies_the_similarity_floor(env):
    store, _, embedder = env
    ing = await ingest(store, embedder)
    await ing.ingest(DocSpec(file="care.md", source="care.md", document_type="care", sport="Cricket", category="Bat", title="Bat Care"), GUIDE.encode())
    await ing.ingest(DocSpec(file="policy.md", source="policy.md", document_type="policy", title="Return Policy"), POLICY.encode())
    retriever = Retriever(store, embedder, 20, 0.25)
    r = await retriever.search_chunks("how long can I return a delivered product", limit=3)
    assert r.hits[0].payload["source"] == "policy.md" and r.hits[0].payload["page"] == 1 and r.hits[0].payload["document_type"] == "policy"
    only_care = await retriever.search_chunks("bat", document_types=["care"], limit=3)
    assert {h.payload["source"] for h in only_care.hits} == {"care.md"}
    football = await retriever.search_chunks("knock in the bat", sport="Football", limit=3)
    assert "care.md" not in {h.payload["source"] for h in football.hits}                 # sport-specific doc excluded; general docs still allowed
    nothing = await retriever.search_chunks("quantum chromodynamics lattice", limit=3)
    assert nothing.hits == []                                                             # weak match with no keyword support is dropped


async def test_product_specific_documents_only_serve_that_product(env):
    store, _, embedder = env
    ing = await ingest(store, embedder)
    pid = str(uuid.uuid4())
    await ing.ingest(DocSpec(file="sheet.md", source="sheet.md", document_type="care"), b"# Sheet\n\nKnock in this bat for six hours.", product_id=pid)
    retriever = Retriever(store, embedder, 20, 0.0)
    assert (await retriever.search_chunks("knock in bat", product_id=pid)).hits
    assert (await retriever.search_chunks("knock in bat", product_id=str(uuid.uuid4()))).hits == []


# ---------------------------------------------------------------- embedding model / dimension change

async def test_changing_the_embedding_dimension_triggers_a_full_reindex(env):
    store, client, embedder = env
    dsports, catalog, indexer, _ = await indexed_products(store, embedder)
    ing = await ingest(store, embedder)
    await ing.ingest(DocSpec(file="care.md", source="care.md", document_type="care"), GUIDE.encode())
    assert await store.check_embedding_config("bow-test", 768) == "initialized"

    small_client = BowClient(dim=256)
    small = Embedder(small_client, "bow-small", 256, cache_ttl_s=0)
    assert await store.check_embedding_config("bow-small", 256) == "reindex_required"
    summary = await reindex(store, ProductIndexer(store, catalog, small), small)
    assert summary.resized is True and summary.previous == "bow-test|768" and summary.products == len(DEFAULT_PRODUCTS) and summary.chunks >= 1
    assert await store.check_embedding_config("bow-small", 256) == "ok"
    assert await store.pool.fetchval("SELECT vector_dims(embedding) FROM product_index LIMIT 1") == 256
    assert await store.pool.fetchval("SELECT vector_dims(embedding) FROM chunks LIMIT 1") == 256
    r = await Retriever(store, small, 20, 0.0).search_products("cricket bat", sport="Cricket")
    assert r.hits and await store.pool.fetchval("SELECT count(*) FROM documents WHERE embedding_model='bow-small'") == 1


async def test_a_vector_of_the_wrong_dimension_is_refused_before_storage(env):
    store, _, _ = env
    wrong = Embedder(BowClient(dim=256), "bow", 768, cache_ttl_s=0)
    with pytest.raises(LLMError) as e:
        await wrong.embed_query("anything")
    assert e.value.code == "EMBEDDING_DIMENSION_MISMATCH"


# ---------------------------------------------------------------- conversations, pending actions, audit (PostgreSQL)

async def test_conversation_state_is_owner_scoped_and_expires(env):
    store, _, _ = env
    state = PgStateStore(store.pool, ttl_hours=24)
    cid = str(uuid.uuid4())
    await state.save(ConversationState(cid, "owner-a", intent="PRODUCT_SEARCH", slots={"search": {"max_price": 8000.0}}, rolling_summary="Looking for a bat.",
                                       last_products=[{"id": "p1", "name": "MRF"}], turns=1))
    mine = await state.load(cid, "owner-a")
    assert mine.slots["search"]["max_price"] == 8000.0 and mine.rolling_summary == "Looking for a bat." and mine.last_products[0]["name"] == "MRF"
    assert await state.load(cid, "owner-b") is None and await state.load(cid, GUEST) is None
    assert await state.load("not-a-uuid", "owner-a") is None
    await store.pool.execute("UPDATE conversations SET expires_at = now() - interval '1 minute' WHERE id=$1", uuid.UUID(cid))
    assert await state.load(cid, "owner-a") is None
    # another owner cannot overwrite it by saving the same id
    await store.pool.execute("UPDATE conversations SET expires_at = now() + interval '1 day' WHERE id=$1", uuid.UUID(cid))
    await state.save(ConversationState(cid, "owner-b", intent="HIJACK"))
    assert (await state.load(cid, "owner-a")).intent == "PRODUCT_SEARCH"


def pending(nonce, owner="owner-a", minutes=5):
    return PendingAction(nonce, owner, None, "create_return", {"quantity": 1}, "hash", "Return 1", datetime.now(timezone.utc) + timedelta(minutes=minutes))


async def test_a_pending_action_can_be_consumed_exactly_once_even_concurrently(env):
    store, _, _ = env
    state = PgStateStore(store.pool)
    await state.create_pending(pending("n1"))
    results = await asyncio.gather(*(state.consume_pending("n1", "owner-a") for _ in range(12)))
    assert len([r for r in results if r is not None]) == 1
    assert await state.consume_pending("n1", "owner-a") is None


async def test_a_pending_action_is_bound_to_its_customer_and_expiry(env):
    store, _, _ = env
    state = PgStateStore(store.pool)
    await state.create_pending(pending("n2"))
    assert await state.consume_pending("n2", "owner-b") is None
    assert (await state.peek_pending("n2")).status == "PENDING"
    await state.create_pending(pending("n3", minutes=-1))
    assert await state.consume_pending("n3", "owner-a") is None
    assert (await state.consume_pending("n2", "owner-a")).args == {"quantity": 1}


async def test_audit_rows_are_written_with_the_correlation_id(env):
    store, _, _ = env
    from app.obs.context import correlation_id_var
    state = PgStateStore(store.pool)
    token = correlation_id_var.set("trace-audit-1")
    try:
        await state.audit(customer_hash="h1", action="PROPOSED", outcome="PENDING_CONFIRMATION", tool="create_return", args_hash="abc", detail={"summary": "x"})
    finally:
        correlation_id_var.reset(token)
    row = await store.pool.fetchrow("SELECT * FROM audit")
    assert (row["customer_hash"], row["action"], row["tool"], row["correlation_id"]) == ("h1", "PROPOSED", "create_return", "trace-audit-1")
