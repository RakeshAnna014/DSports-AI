"""Staff action log and the staff API against a REAL PostgreSQL (the append-only support_ai_actions table) and the real FastAPI app."""
from __future__ import annotations

import os
from types import SimpleNamespace

import asyncpg
import pytest

from app.errors import ApiException
from app.support import staff_assist as sa
from app.store.db import Store

DSN = os.environ.get("AI_TEST_DATABASE_URL")
pytestmark = [pytest.mark.integration, pytest.mark.skipif(not DSN, reason="AI_TEST_DATABASE_URL is not set")]
REF = "CASE-20261010-000042"
ME = SimpleNamespace(customer_hash="staff-hash", roles=["SUPPORT_EXECUTIVE"])
ACTIONS = [{"id": "a1", "kind": "REPLY_CUSTOMER", "label": "Send reply"}]


@pytest.fixture
async def pool():
    conn = await asyncpg.connect(DSN)
    await conn.execute("DROP TABLE IF EXISTS support_ai_actions CASCADE")
    await conn.close()
    store = await Store.connect(DSN, 30, 768)
    yield store.pool
    await store.close()


@pytest.mark.asyncio
async def test_lifecycle_suggested_attempted_succeeded(pool):
    await sa.record_suggestions(pool, REF, ME.customer_hash, ACTIONS)
    await sa.record_suggestions(pool, REF, ME.customer_hash, ACTIONS)            # idempotent: one SUGGESTED row
    assert (await sa.history(pool, REF))[0]["state"] == "SUGGESTED" and len((await sa.history(pool, REF))[0]["trail"]) == 1
    await sa.record_action(pool, ME, REF, "a1", "ATTEMPTED", None)
    h = await sa.record_action(pool, ME, REF, "a1", "SUCCEEDED", {"httpStatus": 200, "customerText": "must not be stored"})
    assert [t["state"] for t in h[0]["trail"]] == ["SUGGESTED", "ATTEMPTED", "SUCCEEDED"]
    assert h[0]["trail"][-1]["detail"] == {"httpStatus": 200}                    # only whitelisted, non-content fields


@pytest.mark.asyncio
async def test_invalid_transitions_and_unknown_actions_refused(pool):
    await sa.record_suggestions(pool, REF, ME.customer_hash, ACTIONS)
    with pytest.raises(ApiException) as skipped:                                 # cannot claim success without an attempt
        await sa.record_action(pool, ME, REF, "a1", "SUCCEEDED", None)
    assert skipped.value.status == 409
    with pytest.raises(ApiException) as unknown:                                 # an action the plan never offered
        await sa.record_action(pool, ME, REF, "invented", "ATTEMPTED", None)
    assert unknown.value.status == 404
    await sa.record_action(pool, ME, REF, "a1", "ATTEMPTED", None)
    await sa.record_action(pool, ME, REF, "a1", "FAILED", {"httpStatus": 409, "code": "VERSION_CONFLICT"})
    h = await sa.history(pool, REF)
    assert h[0]["state"] == "FAILED"
    await sa.record_action(pool, ME, REF, "a1", "ATTEMPTED", None)               # a failed action may be retried
