"""Internal support documents (document_type sup_*) against a REAL PostgreSQL: the separation from customer retrieval is enforced in SQL, in both directions,
and it does not depend on what the caller asks for."""
import os

import pytest

from app.rag.embedder import Embedder
from app.rag.ingest import DocSpec, Ingestor
from app.rag.retrieval import Retriever
from app.store.db import Store
from tests.conftest import make_settings
from tests.integration.test_inventory_db import INV_TABLES
from tests.integration.test_rag_db import BowClient, FakeTika

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]

CUSTOMER_TEXT = b"Missing delivery procedure: if your order is marked delivered but you did not receive it, raise a support case and a person reviews the delivery records."
INTERNAL_TEXT = (b"Escalation matrix for staff: order marked delivered but not received is escalated because delivery proof is not available to the assistant. "
                 b"Do not tell the customer about internal priority rules.")
INVENTORY_TEXT = b"Replenishment policy for staff: order delivered stock quantity must be reviewed by the inventory manager."


@pytest.fixture
async def world():
    import asyncpg
    conn = await asyncpg.connect(DSN)
    for t in (*INV_TABLES, "chunks", "documents", "product_index", "pending_actions", "usage", "audit", "conversations", "ai_meta"):
        await conn.execute(f"DROP TABLE IF EXISTS {t} CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    embedder = Embedder(BowClient(), "bow-test", 768, cache_ttl_s=0)
    ingestor = Ingestor(store, FakeTika(), embedder, make_settings(AI_CHUNK_TOKENS=120, AI_CHUNK_OVERLAP_TOKENS=20))
    await ingestor.ingest(DocSpec(file="a.md", source="missing-delivery.md", document_type="procedure", title="Missing delivery procedure"), CUSTOMER_TEXT)
    await ingestor.ingest(DocSpec(file="b.md", source="sup-escalation.md", document_type="sup_procedure", title="Escalation matrix"), INTERNAL_TEXT)
    await ingestor.ingest(DocSpec(file="c.md", source="inv-replenishment.md", document_type="inv_policy", title="Replenishment policy"), INVENTORY_TEXT)
    yield Retriever(store, embedder, 20, 0.0)
    await store.close()


QUERY = "order marked delivered but not received delivery escalation"


async def test_a_customer_search_never_returns_an_internal_document_of_either_family(world):
    hits = (await world.search_chunks(QUERY, limit=10)).hits
    assert hits and {h.payload["source"] for h in hits} == {"missing-delivery.md"}
    assert all(not h.payload["document_type"].startswith(("sup_", "inv_")) for h in hits)


async def test_asking_for_an_internal_type_by_name_changes_nothing_for_a_customer(world):
    for types in (["sup_procedure"], ["inv_policy"], ["sup_procedure", "procedure"]):
        hits = (await world.search_chunks(QUERY, document_types=types, limit=10)).hits
        assert all(h.payload["source"] == "missing-delivery.md" for h in hits), types


async def test_the_support_staff_search_returns_only_support_documents(world):
    hits = (await world.search_chunks(QUERY, limit=10, internal="sup_")).hits
    assert hits and {h.payload["source"] for h in hits} == {"sup-escalation.md"} and all(h.payload["document_type"] == "sup_procedure" for h in hits)


async def test_the_inventory_search_does_not_see_support_documents_and_the_reverse(world):
    inv = (await world.search_chunks("replenishment inventory manager order delivered", limit=10, internal=True)).hits
    assert inv and {h.payload["source"] for h in inv} == {"inv-replenishment.md"}
    sup = (await world.search_chunks("replenishment inventory manager order delivered", limit=10, internal="sup_")).hits
    assert all(h.payload["source"] != "inv-replenishment.md" for h in sup)


async def test_an_unknown_internal_prefix_fails_closed_instead_of_being_interpolated(world):
    with pytest.raises(KeyError):
        await world.search_chunks(QUERY, internal="x%' OR 1=1 --")
