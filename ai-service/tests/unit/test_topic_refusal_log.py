"""The refused-message log: what is stored (topic, role, redacted text, time), what never is (any identity), and that only an administrator can read it."""
import pytest
from fastapi.testclient import TestClient

from app.agent.schemas import ChatRequest
from app.guard.topic_log import MAX_TEXT, RefusalLog, clean
from app.main import create_app
from tests.conftest import make_settings, make_token
from tests.helpers import make_rig

ADMIN_SUB = "cccccccc-0000-0000-0000-000000000003"


async def say(rig, message, principal=None):
    return await rig.chat.chat(principal, ChatRequest(message=message))


# ---------------------------------------------------------------- what is stored

def test_the_text_is_redacted_flattened_and_bounded():
    text = clean("what is the capital of India?   mail me at asha.rao@example.com or call 98765 43210\n\nthanks " + "x" * 400)
    assert "@" not in text and "98765" not in text and "\n" not in text and len(text) <= MAX_TEXT
    assert "[number]" in text and text.startswith("what is the capital of India?")


async def test_an_off_topic_message_is_recorded_with_topic_role_text_and_time_and_nothing_else():
    rig = await make_rig()
    await say(rig, "what is the capital of India", rig.alice)
    rows = await rig.services.refusals.recent()
    assert len(rows) == 1 and set(rows[0]) == {"time", "topic", "role", "text"}
    assert (rows[0]["topic"], rows[0]["role"], rows[0]["text"]) == ("OFF_TOPIC", "CUSTOMER", "what is the capital of India")


async def test_a_business_question_from_a_guest_is_recorded_as_business_with_the_guest_role():
    rig = await make_rig()
    await say(rig, "what were our total sales last month")
    rows = await rig.services.refusals.recent()
    assert [(r["topic"], r["role"]) for r in rows] == [("BUSINESS", "GUEST")]


async def test_nothing_that_identifies_the_asker_is_stored():
    rig = await make_rig()
    r = await say(rig, "tell me a joke", rig.alice)
    row = (await rig.services.refusals.recent())[0]
    blob = repr(row)
    assert r.conversation_id not in blob and rig.alice.customer_hash not in blob and str(rig.alice.user_id) not in blob


async def test_answered_messages_and_unsure_replies_are_not_recorded():
    rig = await make_rig()
    await say(rig, "show me cricket bats under 15000", rig.alice)
    await say(rig, "what is LBW", rig.alice)
    assert await rig.services.refusals.recent() == []


async def test_a_failed_write_never_changes_the_reply():
    class Broken:
        async def execute(self, *a, **k):
            raise RuntimeError("database down")
    log = RefusalLog(Broken())
    await log.record(topic="OFF_TOPIC", role="GUEST", text="tell me a joke")        # must not raise
    rig = await make_rig()
    rig.services.refusals = log
    r = await say(rig, "tell me a joke", rig.alice)
    assert r.status == "refused"


async def test_filters_limit_and_order_newest_first():
    log = RefusalLog()
    for i in range(5):
        await log.record(topic="OFF_TOPIC" if i % 2 == 0 else "BUSINESS", role="GUEST" if i < 3 else "CUSTOMER", text=f"message {i}")
    assert [r["text"] for r in await log.recent(limit=2)] == ["message 4", "message 3"]
    assert {r["topic"] for r in await log.recent(topic="BUSINESS")} == {"BUSINESS"}
    assert {r["role"] for r in await log.recent(role="CUSTOMER")} == {"CUSTOMER"}


# ---------------------------------------------------------------- who can read it

@pytest.fixture
def client():
    with TestClient(create_app(make_settings()), raise_server_exceptions=False) as c:
        c.app.state.services.refusals = RefusalLog()
        yield c


def _bearer(sub, *roles):
    return {"Authorization": f"Bearer {make_token(sub, roles=roles)}"}


def test_only_an_administrator_can_read_the_log(client):
    assert client.get("/v1/topic/refusals").status_code == 401
    assert client.get("/v1/topic/refusals", headers=_bearer("11111111-0000-0000-0000-000000000001", "CUSTOMER")).status_code == 403
    assert client.get("/v1/topic/refusals", headers=_bearer("11111111-0000-0000-0000-000000000002", "SUPPORT_EXECUTIVE")).status_code == 403
    assert client.get("/v1/topic/refusals", headers=_bearer(ADMIN_SUB, "ADMIN")).status_code == 200


def test_an_unknown_filter_value_is_a_4xx_never_a_500(client):
    admin = _bearer(ADMIN_SUB, "ADMIN")
    for query in ("topic=SHOP", "role=ROOT", "limit=0", "limit=abc", "since=yesterday", "format=xml"):
        assert 400 <= client.get(f"/v1/topic/refusals?{query}", headers=admin).status_code < 500, query


def test_the_csv_export_has_the_four_columns_and_defuses_spreadsheet_formulas():
    import asyncio
    log = RefusalLog()
    asyncio.run(log.record(topic="OFF_TOPIC", role="GUEST", text="=HYPERLINK(\"http://x\")"))
    with TestClient(create_app(make_settings())) as c:
        c.app.state.services.refusals = log
        r = c.get("/v1/topic/refusals?format=csv", headers=_bearer(ADMIN_SUB, "ADMIN"))
    lines = r.text.strip().splitlines()
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv") and lines[0] == "time,topic,role,text"
    assert lines[1].endswith(",OFF_TOPIC,GUEST,\"'=HYPERLINK(\"\"http://x\"\")\"")
