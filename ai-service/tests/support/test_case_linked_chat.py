"""The support assistant opened from one of the customer's own cases: case-linked, resumable, never a second ticket, writes only after confirmation."""
from __future__ import annotations

import pytest

from app.agent.schemas import ChatRequest
from app.errors import ApiException
from tests.support.conftest_support import BAT_MRF, USER_A, confirm, support_rig
from tests.support.test_cases_conversation import open_case

CASE_LAST_ORDER = "ORD-20261001-000123"


async def chat(rig, text, case_ref, *, conv=None, who="alice"):
    principal = rig.alice if who == "alice" else rig.bob
    return await rig.chat.chat(principal, ChatRequest(message=text, conversation_id=conv, case_ref=case_ref))


async def rig_with_case(**kw):
    rig = await support_rig(**kw)
    _, case, _ = await open_case(rig)
    return rig, case["caseNumber"], case


async def test_opening_the_assistant_from_a_case_links_the_conversation_and_answers_about_that_case():
    rig, ref, case = await rig_with_case()
    r = await chat(rig, "What is the latest on this case?", ref)
    assert r.support["kind"] == "CASE" and ref in r.message
    conv = await rig.services.state.load(r.conversation_id, rig.chat._owner(rig.alice))
    assert conv.slots["case_ref"] == ref and conv.slots["support"]["case_number"] == ref
    assert conv.ttl_hours and conv.ttl_hours > 24                                    # long enough to resume later
    audited = [a for a in getattr(rig.services.state, "audit_log", []) if a["action"] == "SUPPORT_ASSIST_CASE_LINKED"]
    assert audited and audited[-1]["detail"]["case_number"] == ref


async def test_someone_elses_case_is_never_opened_and_nothing_about_it_leaks():
    rig, ref, _ = await rig_with_case()
    r = await chat(rig, "What is the latest on this case?", ref, who="bob")
    assert "could not open that case" in " ".join(r.disclaimers)
    assert ref not in r.message and "delivered" not in r.message.lower() and "MRF" not in r.message
    conv = await rig.services.state.load(r.conversation_id, rig.chat._owner(rig.bob))
    assert "case_ref" not in conv.slots


async def test_a_malformed_case_reference_is_ignored_not_passed_to_the_backend():
    rig, _, _ = await rig_with_case()
    before = len(rig.dsports.requests) if hasattr(rig.dsports, "requests") else None
    r = await chat(rig, "hello there, what can you do?", "../../admin")
    assert "could not use that case" in " ".join(r.disclaimers)
    if before is not None:
        assert not any("admin" in str(x) for x in rig.dsports.requests[before:])


async def test_the_conversation_can_be_resumed_from_the_case_with_its_transcript():
    rig, ref, _ = await rig_with_case()
    empty = await rig.chat.case_conversation(rig.alice, ref)
    assert empty == {"caseRef": ref, "conversationId": None, "transcript": []}
    r = await chat(rig, "What is the latest on this case?", ref)
    again = await rig.chat.case_conversation(rig.alice, ref)
    assert again["conversationId"] == r.conversation_id
    roles = [m["role"] for m in again["transcript"]]
    assert roles == ["user", "assistant"] and again["transcript"][0]["text"] == "What is the latest on this case?"
    assert ref in again["transcript"][1]["text"]
    r2 = await chat(rig, "Add a message to my case", ref, conv=again["conversationId"])      # continuing keeps the same conversation
    assert r2.conversation_id == r.conversation_id
    assert len((await rig.chat.case_conversation(rig.alice, ref))["transcript"]) == 4


async def test_resuming_is_only_possible_for_the_owner_and_a_bad_reference_is_a_client_error():
    rig, ref, _ = await rig_with_case()
    await chat(rig, "What is the latest on this case?", ref)
    with pytest.raises(ApiException) as foreign:
        await rig.chat.case_conversation(rig.bob, ref)
    assert foreign.value.status == 404
    with pytest.raises(ApiException) as bad:
        await rig.chat.case_conversation(rig.alice, "not-a-case")
    assert bad.value.status == 400


async def test_check_this_again_investigates_the_cases_own_order_from_the_cases_description():
    rig, ref, case = await rig_with_case()
    first = await chat(rig, "What is the latest on this case?", ref)
    chip = next(s for s in first.suggestions if s.label == "Check this again")
    r = await rig.chat.chat(rig.alice, ChatRequest(message="Check this again", conversation_id=first.conversation_id, case_ref=ref, choice_id=chip.choice_id))
    assert r.support["kind"] == "INVESTIGATION" and r.support["order"]["order_number"] == CASE_LAST_ORDER


async def test_a_problem_in_a_case_chat_never_opens_a_second_case_it_asks_for_a_review_of_this_one_after_confirmation():
    rig, ref, case = await rig_with_case()
    r = await chat(rig, "My order says delivered but I did not receive it. Please get a person to look at it.", ref)
    assert len(rig.dsports.support_cases) == 1
    assert [a.tool for a in r.actions] in ([], ["request_case_escalation"])               # never create_support_case
    assert "create_support_case" not in {a.tool for a in r.actions}
    assert not any(s.label == "Raise a support case about this" for s in r.suggestions)
    if r.actions:
        assert r.requires_confirmation and case["escalation"]["state"] == "NONE"           # nothing changed before the customer confirms
        done = await confirm(rig, r)
        assert len(rig.dsports.support_cases) == 1
        assert rig.dsports.support_cases[0]["escalation"]["state"] == "REQUESTED"           # a request; nobody has accepted it
        assert "recorded your request" in done.message and "assigned" not in done.message.lower()


async def test_adding_a_message_to_the_case_is_a_confirmed_write_for_the_customers_own_case_only():
    rig, ref, case = await rig_with_case()
    r = await chat(rig, "Add a message to my case: the parcel box was empty when I opened it", ref)
    assert r.requires_confirmation and r.actions[0].tool == "add_case_message"
    count = len(rig.dsports.support_cases[0]["msgs"])
    assert len(rig.dsports.support_cases[0]["msgs"]) == count                      # not yet
    done = await confirm(rig, r)
    assert "added your message" in done.message
    assert len(rig.dsports.support_cases[0]["msgs"]) == count + 1
    stranger = await chat(rig, "Add a message to my case: hello", ref, who="bob")
    assert not stranger.actions                                                                # Bob can not write to Alice's case


async def test_a_resolved_case_offers_reopening_when_the_backend_allows_it():
    rig, ref, case = await rig_with_case()
    rig.dsports.staff_take(case["id"])
    rig.dsports.staff_resolve(case["id"], "DELIVERY_CONFIRMED", "The courier confirmed the parcel was left at the front desk.")
    r = await chat(rig, "I still do not have it", ref)
    assert any(s.label == "Reopen this case" for s in r.suggestions) == bool(rig.dsports.support_cases[0].get("canReopen", True))


class _Repo:
    def __init__(self, clusters=None, boom=False):
        self._clusters, self._boom, self.filters = clusters or [], boom, []

    async def clusters(self, f, limit=15):
        self.filters.append(f)
        if self._boom:
            raise RuntimeError("log store down")
        return self._clusters


async def test_a_payment_complaint_gets_one_hedged_customer_safe_sentence_when_real_logs_show_technical_errors():
    rig = await support_rig()
    rig.services.engineering = _Repo([{"category": "DATABASE", "signature": "s", "text": "Pool acquire timed out at jdbc:postgresql://secret-host", "count": 99}])
    rig.dsports.add_order(USER_A, "ORD-20261002-000200", "PENDING_PAYMENT", [(BAT_MRF, 1)])
    r = await rig.chat.chat(rig.alice, ChatRequest(message="My payment was deducted but my order failed"))
    assert "recent technical errors" in r.message and "not confirmed" in r.message and "nothing has been fixed" in r.message
    for leaked in ("secret-host", "jdbc", "Pool acquire", "99", "signature"):
        assert leaked not in r.message
    assert rig.services.engineering.filters[0].origin == "real"                              # synthetic records are never presented as live
    assert rig.services.engineering.filters[0].services == ("backend",)


async def test_no_signal_when_logs_are_quiet_unavailable_or_the_complaint_is_not_technical():
    for repo in (_Repo([]), _Repo(boom=True), _Repo([{"category": "VALIDATION", "signature": "s", "text": "x", "count": 5}])):
        rig = await support_rig()
        rig.services.engineering = repo
        rig.dsports.add_order(USER_A, "ORD-20261002-000200", "PENDING_PAYMENT", [(BAT_MRF, 1)])
        r = await rig.chat.chat(rig.alice, ChatRequest(message="My payment was deducted but my order failed"))
        assert "recent technical errors" not in r.message
    rig = await support_rig()
    rig.services.engineering = _Repo([{"category": "DATABASE", "signature": "s", "text": "x", "count": 5}])
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await rig.chat.chat(rig.alice, ChatRequest(message="My order says delivered, but I did not receive it."))
    assert "recent technical errors" not in r.message and not rig.services.engineering.filters       # a delivery complaint never consults the logs
