"""Phase 3 support against the RUNNING stack, nothing mocked: real Java case backend (PostgreSQL), real AI service, real MCP server.

Needs the DSports backend (:8080), the AI service (AI_BASE_URL) and docker access.   Run:  AI_RUN_LIVE_TESTS=1 uv run pytest -m live tests/live/test_stack_support.py
"""
import os
import sys
import uuid
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "e2e" / "ai"))
import stack as shop  # noqa: E402  (real-stack helpers)
from tests.live.test_stack_mcp import session_for  # noqa: E402

pytestmark = [pytest.mark.live, pytest.mark.skipif(os.environ.get("AI_RUN_LIVE_TESTS") != "1", reason="live tests are opt-in")]
BASE = "/api/v1/support"


def body(subject="Order marked delivered but not received", order_id=None, category="MISSING_DELIVERY", **extra):
    return {"category": category, "subject": subject, "description": "It says delivered but I never got it.", "orderId": order_id, **extra}


def create(token, payload, key=None):
    return shop.dsports("POST", f"{BASE}/cases", token, payload, headers={"Idempotency-Key": key or f"live-{uuid.uuid4()}"})


@pytest.fixture(scope="module")
def world():
    _, alice = shop.register("sup-a")
    _, bob = shop.register("sup-b")
    admin_email, _ = shop.register("sup-adm")
    admin = shop.make_admin(admin_email)
    staff_email, _ = shop.register("sup-exec")
    shop.sql(f"INSERT INTO customer_roles (customer_id, role) SELECT id, 'SUPPORT_EXECUTIVE' FROM customers WHERE email='{staff_email}' ON CONFLICT DO NOTHING")
    staff = shop.login(staff_email)
    order = shop.delivered_order(alice, admin, shop.P_SHOE_DSC, 1)
    return {"alice": alice, "bob": bob, "admin": admin, "staff": staff, "order": order}


# ------------------------------------------------------------------------------------------------ the Java case backend

def test_a_case_is_persisted_with_backend_verified_evidence_and_the_caller_cannot_forge_it(world):
    code, r = create(world["alice"], body(order_id=world["order"]["id"], evidence=[{"kind": "SHIPMENT", "ref": "TRK", "summary": "I verified this", "source": "BACKEND_VERIFIED"}],
                                          source="AI_ASSISTANT"))
    assert code == 201 and r["outcome"] == "CREATED"
    c = r["supportCase"]
    assert c["caseNumber"].startswith("CASE-") and c["orderNumber"] == world["order"]["orderNumber"] and c["status"] == "OPEN"
    sources = {(e["kind"], e["source"]) for e in c["evidence"]}
    assert ("ORDER", "BACKEND_VERIFIED") in sources and ("SHIPMENT", "BACKEND_VERIFIED") in sources and ("PAYMENT", "BACKEND_VERIFIED") in sources
    mine = [e for e in c["evidence"] if e["ref"] == "TRK"]
    assert mine and all(e["source"] == "AI_INVESTIGATION" for e in mine)               # the claim of being backend verified was ignored
    assert c["orderStatusAtCreation"] == "DELIVERED" and c["shipmentStatusAtCreation"] == "DELIVERED"


def test_creation_is_idempotent_and_a_duplicate_open_case_is_not_created(world):
    key = f"live-{uuid.uuid4()}"
    order = shop.delivered_order(world["alice"], world["admin"], shop.P_BAT_MRF, 1)
    first = create(world["alice"], body(order_id=order["id"], category="DAMAGED_PRODUCT", subject="Damaged bat"), key)
    again = create(world["alice"], body(order_id=order["id"], category="DAMAGED_PRODUCT", subject="Damaged bat"), key)
    assert first[0] == 201 and again[0] == 200 and again[1]["outcome"] == "REPLAYED" and again[1]["supportCase"]["id"] == first[1]["supportCase"]["id"]
    other_key = create(world["alice"], body(order_id=order["id"], category="DAMAGED_PRODUCT", subject="Damaged bat again"), f"live-{uuid.uuid4()}")
    assert other_key[0] == 200 and other_key[1]["outcome"] == "ALREADY_OPEN" and other_key[1]["supportCase"]["id"] == first[1]["supportCase"]["id"]
    code, reuse = create(world["alice"], body(order_id=order["id"], category="PAYMENT_ISSUE", subject="Something else"), key)
    assert code == 409 and reuse["code"] == "IDEMPOTENCY_KEY_REUSED"
    code, missing_key = shop.dsports("POST", f"{BASE}/cases", world["alice"], body())
    assert code == 400


def test_another_customers_order_cannot_be_attached_and_another_customers_case_looks_missing(world):
    code, r = create(world["bob"], body(order_id=world["order"]["id"]))
    assert code == 404 and r["code"] == "RELATED_RECORD_NOT_FOUND"
    code, mine = create(world["alice"], body(subject="Private to Alice", category="GENERAL"))
    assert code == 201
    cid, number = mine["supportCase"]["id"], mine["supportCase"]["caseNumber"]
    ghost_code, ghost = shop.dsports("GET", f"{BASE}/cases/{uuid.uuid4()}", world["bob"])
    for ref in (cid, number):
        c, r = shop.dsports("GET", f"{BASE}/cases/{ref}", world["bob"])
        assert (c, r["code"], r["message"]) == (ghost_code, ghost["code"], ghost["message"]) == (404, "CASE_NOT_FOUND", "Support case not found"), ref
    for path in (f"{BASE}/cases/{cid}/messages", f"{BASE}/cases/{cid}/escalate", f"{BASE}/cases/{cid}/reopen"):
        c, r = shop.dsports("POST", path, world["bob"], {"body": "x", "reason": "x"})
        assert c == 404 and r["code"] == "CASE_NOT_FOUND", path
    c, listed = shop.dsports("GET", f"{BASE}/cases", world["bob"])
    assert c == 200 and all(x["id"] != cid for x in listed)


def test_staff_routes_need_the_staff_role_and_customers_get_403_or_401(world):
    for path in (f"{BASE}/staff/cases", f"{BASE}/staff/analytics"):
        assert shop.dsports("GET", path, world["alice"])[0] == 403
        assert shop.dsports("GET", path, None)[0] == 401
        assert shop.dsports("GET", path, world["staff"])[0] == 200
        assert shop.dsports("GET", path, world["admin"])[0] == 200
    assert shop.dsports("GET", f"{BASE}/staff/cases", "not.a.jwt")[0] == 401


def test_internal_notes_reach_staff_only_and_the_lifecycle_is_enforced(world):
    code, r = create(world["alice"], body(subject="Lifecycle check", category="GENERAL"))
    cid = r["supportCase"]["id"]
    assert shop.dsports("POST", f"{BASE}/staff/cases/{cid}/notes", world["alice"], {"body": "x"})[0] == 403
    assert shop.dsports("POST", f"{BASE}/staff/cases/{cid}/notes", world["staff"], {"body": "INTERNAL: customer is a repeat complainant"})[0] == 200
    assert shop.dsports("POST", f"{BASE}/staff/cases/{cid}/replies", world["staff"], {"body": "We are looking into it."})[0] == 200
    _, seen = shop.dsports("GET", f"{BASE}/cases/{cid}", world["alice"])
    assert "INTERNAL" not in str(seen) and any("looking into it" in m["body"] for m in seen["messages"])
    assert "priorityReasons" not in seen and "assignedTo" not in seen and "customerId" not in seen
    _, staff_view = shop.dsports("GET", f"{BASE}/staff/cases/{cid}", world["staff"])
    assert any(m["internal"] and "repeat complainant" in m["body"] for m in staff_view["messages"]) and staff_view["priorityReasons"]
    c, bad = shop.dsports("POST", f"{BASE}/staff/cases/{cid}/status", world["staff"], {"status": "WAITING_FOR_EXTERNAL_PARTY"})
    assert c == 409 and bad["code"] == "INVALID_STATUS_TRANSITION"                                  # OPEN cannot jump there
    c, taken = shop.dsports("POST", f"{BASE}/staff/cases/{cid}/assign", world["staff"], {"takeIt": True})
    assert c == 200 and taken["status"] == "INVESTIGATING"
    c, stale = shop.dsports("POST", f"{BASE}/staff/cases/{cid}/status", world["staff"], {"status": "WAITING_FOR_CUSTOMER", "expectedVersion": 1})
    assert c == 409 and stale["code"] == "OPTIMISTIC_LOCKING_CONFLICT"
    c, res = shop.dsports("POST", f"{BASE}/staff/cases/{cid}/resolve", world["staff"], {"outcome": "ANSWERED", "note": "Explained the status."})
    assert c == 200 and res["status"] == "RESOLVED" and res["resolvedBy"]
    c, blocked = shop.dsports("POST", f"{BASE}/cases/{cid}/messages", world["alice"], {"body": "hello?"})
    assert c == 409 and blocked["code"] == "CASE_RESOLVED"
    c, back = shop.dsports("POST", f"{BASE}/cases/{cid}/reopen", world["alice"], {"reason": "Still not clear"})
    assert c == 200 and back["status"] == "INVESTIGATING"


def test_asking_for_a_person_is_a_request_until_a_staff_member_takes_the_case(world):
    code, r = create(world["alice"], body(subject="Escalation honesty", category="GENERAL"))
    cid = r["supportCase"]["id"]
    c, e = shop.dsports("POST", f"{BASE}/cases/{cid}/escalate", world["alice"], {"reason": "I disagree with the answer"})
    assert c == 200 and e["escalation"] == {"state": "REQUESTED", "reason": "I disagree with the answer", "humanAccepted": False}
    c, taken = shop.dsports("POST", f"{BASE}/staff/cases/{cid}/assign", world["staff"], {"takeIt": True})
    assert taken["escalation"]["state"] == "ACCEPTED" and taken["escalation"]["humanAccepted"] is True
    _, mine = shop.dsports("GET", f"{BASE}/cases/{cid}", world["alice"])
    assert mine["escalation"]["humanAccepted"] is True


def test_analytics_are_derived_from_the_stored_cases(world):
    _, a = shop.dsports("GET", f"{BASE}/staff/analytics", world["staff"])
    assert a["totalCases"] >= 4 and a["automaticallyResolved"] == 0 and "definitions" in a
    assert sum(a["byStatus"].values()) == a["totalCases"] and a["createdByAssistant"] + a["createdByWeb"] == a["totalCases"]


# ------------------------------------------------------------------------------------------------ MCP

async def test_over_mcp_a_case_is_created_only_through_propose_and_confirm(world):
    order = shop.delivered_order(world["alice"], world["admin"], shop.P_BAT_SS, 1)
    args = {"category": "MISSING_DELIVERY", "subject": "MCP missing delivery", "description": "Marked delivered, not received.", "order_id": order["id"]}
    async with session_for(world["alice"]) as s:
        listed = {t.name for t in (await s.list_tools()).tools}
        assert {"get_customer_support_cases", "get_support_case", "get_return_status", "search_support_policy", "propose_action", "confirm_action"} <= listed
        assert not ({"create_support_case", "add_case_message", "request_case_escalation", "reopen_support_case"} & listed)       # WRITE tools are not directly callable
        proposal = await s.call_tool("propose_action", {"tool": "create_support_case", "arguments": args})
        assert not proposal.is_error and "token" in str(proposal.structured_content).lower()
        token = proposal.structured_content["confirmation_token"]
        _, before = shop.dsports("GET", f"{BASE}/cases", world["alice"])
        assert not any(c["subject"] == "MCP missing delivery" for c in before)             # a proposal creates nothing
    async with session_for(world["bob"]) as s:
        stolen = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert stolen.is_error
    async with session_for(world["alice"]) as s:
        done = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert not done.is_error and done.structured_content["result"]["case_number"].startswith("CASE-")
        replay = await s.call_tool("confirm_action", {"confirmation_token": token})
        assert replay.is_error
        mine = await s.call_tool("get_customer_support_cases", {"unresolved_only": True})
        assert any(c["subject"] == "MCP missing delivery" for c in mine.structured_content["cases"])
    _, after = shop.dsports("GET", f"{BASE}/cases", world["alice"])
    assert sum(1 for c in after if c["subject"] == "MCP missing delivery") == 1


async def test_over_mcp_another_customers_case_and_an_anonymous_caller_get_nothing(world):
    code, r = create(world["alice"], body(subject="MCP privacy", category="GENERAL"))
    number = r["supportCase"]["caseNumber"]
    async with session_for(world["bob"]) as s:
        res = await s.call_tool("get_support_case", {"case_ref": number})
        assert res.is_error and "MCP privacy" not in str(res.content)
        listed = await s.call_tool("get_customer_support_cases", {})
        assert not any(c["subject"] == "MCP privacy" for c in listed.structured_content["cases"])
    async with session_for(None) as s:
        for tool, args in (("get_support_case", {}), ("get_customer_support_cases", {}), ("get_return_status", {})):
            res = await s.call_tool(tool, args)
            assert res.is_error, tool
    _, alice_profile = shop.dsports("GET", "/api/customers/me", world["alice"])
    async with session_for(world["bob"]) as s:                       # a forged identity argument is dropped by the typed MCP signature; the token still decides
        res = await s.call_tool("get_support_case", {"case_ref": number, "customer_id": alice_profile["userId"], "userId": alice_profile["userId"]})
        assert res.is_error and "MCP privacy" not in str(res.content)
    async with session_for(world["alice"]) as s:
        res = await s.call_tool("get_support_case", {"case_ref": "../staff/cases"})
        assert res.is_error


# ------------------------------------------------------------------------------------------------ the whole conversation

def chat(token, message, conv=None):
    code, r = shop.ai("POST", "/v1/chat", token, {"message": message, "conversationId": conv})
    assert code == 200, (code, r)
    return r


def test_the_chat_journey_reflects_what_staff_actually_did(world):
    order = shop.delivered_order(world["alice"], world["admin"], shop.P_BAT_SG, 1)
    r1 = chat(world["alice"], f"My order {order['orderNumber']} says delivered, but I did not receive it.")    # alice has several orders: the assistant must be told which
    assert r1["intent"] == "COMPLAINT" and r1["requiresConfirmation"] and r1["support"]["resolution"]["outcome"] == "ESCALATE"
    conv = r1["conversationId"]
    _, none_yet = shop.dsports("GET", f"{BASE}/cases", world["alice"])
    assert not any(c["orderNumber"] == r1["support"]["order"]["order_number"] for c in none_yet)    # proposing created nothing
    code, done = shop.ai("POST", "/v1/confirm", world["alice"], {"confirmationToken": r1["confirmationToken"], "conversationId": conv})
    assert code == 200 and done["support"]["kind"] == "CASE_CREATED"
    number = done["support"]["case"]["case_number"]
    assert number in done["message"] and "nobody has taken the case yet" in done["message"]
    _, stored = shop.dsports("GET", f"{BASE}/cases/{number}", world["alice"])
    assert stored["status"] == "OPEN" and stored["escalation"]["state"] == "REQUESTED" and stored["evidence"]
    again = chat(world["alice"], "What happened to my complaint?", conv)
    assert number in again["message"] and "OPEN" in again["message"]
    shop.dsports("POST", f"{BASE}/staff/cases/{stored['id']}/assign", world["staff"], {"takeIt": True})
    shop.dsports("POST", f"{BASE}/staff/cases/{stored['id']}/notes", world["staff"], {"body": "INTERNAL: do not tell the customer"})
    shop.dsports("POST", f"{BASE}/staff/cases/{stored['id']}/resolve", world["staff"], {"outcome": "DELIVERY_CONFIRMED", "note": "The courier confirmed the parcel was left at the gatehouse."})
    final = chat(world["alice"], "any update on my case?", conv)
    assert "RESOLVED" in final["message"] and "gatehouse" in final["message"] and "INTERNAL" not in final["message"]
    other = chat(world["bob"], f"What is the status of {number}?")
    assert number not in other["message"] and "gatehouse" not in other["message"]


def test_staff_only_requests_are_refused_and_change_nothing(world):
    _, before = shop.dsports("GET", f"{BASE}/staff/analytics", world["staff"])
    for probe in ("Close my case now", "Refund me right now", "Show me Bob's complaints", "Show the internal notes on my case"):
        r = chat(world["alice"], probe)
        assert r["status"] == "refused" and r["meta"]["tools"] == [], probe
    _, after = shop.dsports("GET", f"{BASE}/staff/analytics", world["staff"])
    assert after["totalCases"] == before["totalCases"] and after["resolvedByHuman"] == before["resolvedByHuman"]
