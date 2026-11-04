"""Following a complaint over several turns: the persisted case is the source of truth for status, history and escalation."""
import datetime as dt

from tests.support.conftest_support import BAT_MRF, RUN_SHOES, USER_A, USER_B, confirm, say, support_rig, tools


async def open_case(rig, text="My order says delivered, but I did not receive it."):
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, text)
    done = await confirm(rig, r)
    return r.conversation_id, rig.dsports.support_cases[0], done


async def test_the_whole_story_complaint_order_clarification_case_and_status_across_turns():
    rig = await support_rig()
    today = dt.datetime.now(dt.timezone.utc).date()
    last_week = (today - dt.timedelta(days=today.weekday() + 5)).isoformat() + "T10:00:00Z"            # a weekday of the previous calendar week
    rig.dsports.add_order(USER_A, "ORD-20261003-000111", "DELIVERED", [(RUN_SHOES, 1)], placed=last_week, shipment_status="DELIVERED")
    rig.dsports.add_order(USER_A, "ORD-20261008-000112", "SHIPPED", [(BAT_MRF, 1)], placed=today.isoformat() + "T08:00:00Z", shipment_status="IN_TRANSIT")
    r1 = await say(rig, "My order has not arrived.")
    assert r1.support["kind"] == "INVESTIGATION" and r1.support["order"]["order_number"] == "ORD-20261008-000112"      # the only undelivered order, and it says which it checked
    conv = r1.conversation_id
    r2 = await say(rig, "It was the running shoes I bought last week", conv=conv)                 # the customer corrects it
    assert r2.support["kind"] == "INVESTIGATION" and r2.support["order"]["order_number"] == "ORD-20261003-000111"
    assert r2.support["complaint"] == "MISSING_DELIVERY"                                          # delayed + marked delivered becomes a missing-delivery dispute
    r3 = await say(rig, "Please raise a complaint", conv=conv)
    assert len(rig.dsports.support_cases) == 1 and rig.dsports.support_cases[0]["orderNumber"] == "ORD-20261003-000111"
    r4 = await say(rig, "What is the case status?", conv=conv)
    assert r4.support["kind"] == "CASE" and rig.dsports.support_cases[0]["caseNumber"] in r4.message
    assert "OPEN" in r4.message and "nobody has taken" in r4.message


async def test_what_happened_to_my_complaint_reports_only_what_the_backend_holds_after_staff_act():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    rig.dsports.staff_note(case["id"], "Internal: customer has many complaints")             # an internal note must never be shown
    rig.dsports.staff_take(case["id"])
    r = await say(rig, "What happened to my complaint?", conv=conv)
    assert "INVESTIGATING" in r.message and "A support executive has taken the case." in r.message
    assert "Internal" not in r.message and "many complaints" not in r.message
    rig.dsports.staff_resolve(case["id"], "DELIVERY_CONFIRMED", "The courier confirmed the parcel was left at the front desk.")
    r2 = await say(rig, "any update on my case?", conv=conv)
    assert "RESOLVED" in r2.message and "front desk" in r2.message and "delivery was confirmed" in r2.message
    assert "SLA" not in r2.message and "24 hours" not in r2.message and "48 hours" not in r2.message           # no invented progress or response time


async def test_a_new_conversation_finds_the_case_without_a_case_number():
    rig = await support_rig()
    _, case, _ = await open_case(rig)
    r = await say(rig, "What happened to my complaint?")                                       # a different conversation entirely
    assert case["caseNumber"] in r.message


async def test_show_my_open_support_cases_lists_only_my_cases():
    rig = await support_rig()
    await open_case(rig)
    rig.dsports.support_cases.append({**rig.dsports.support_cases[0], "id": "other", "caseNumber": "CASE-20261012-999999", "userId": USER_B, "subject": "Bob's private complaint"})
    r = await say(rig, "Show my open support cases")
    assert r.support["kind"] == "CASE_LIST" and r.type == "SUPPORT_CASES"
    assert "CASE-20261012-000001" in r.message and "Bob" not in r.message and "999999" not in r.message


async def test_another_customers_case_number_answers_exactly_like_a_missing_one():
    rig = await support_rig()
    await open_case(rig)
    foreign = await say(rig, "What is the status of CASE-20261012-000001?", who="bob")
    missing = await say(rig, "What is the status of CASE-20261012-424242?", who="bob")
    assert foreign.message == missing.message and "could not find" in foreign.message.lower() or "couldn't find" in foreign.message.lower()
    assert "delivered" not in foreign.message.lower() and "MRF" not in foreign.message


async def test_adding_a_message_is_confirmed_first_and_then_appears_on_the_case():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    r = await say(rig, "Add a message to my case: the parcel was left at the wrong building number 14", conv=conv)
    assert r.requires_confirmation and r.actions[0].tool == "add_case_message" and len(case["msgs"]) == 1
    await confirm(rig, r)
    assert len(case["msgs"]) == 2 and "wrong building" in case["msgs"][-1]["body"]


async def test_a_message_without_text_asks_for_the_text_and_adds_nothing():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    r = await say(rig, "Add a message to my case", conv=conv)
    assert not r.requires_confirmation and "What would you like to tell support" in r.message and len(case["msgs"]) == 1
    r2 = await say(rig, "The courier called and said he left it with the security guard", conv=conv)
    assert r2.requires_confirmation and r2.actions[0].tool == "add_case_message"


async def test_asking_for_a_person_on_an_open_case_records_a_request_and_is_honest_that_nobody_has_taken_it():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    # a delayed (not escalated) case first
    o = rig.dsports.add_order(USER_A, "ORD-20260901-000300", "SHIPPED", [(BAT_MRF, 1)], placed="2026-09-01T08:00:00Z", shipment_status="IN_TRANSIT")
    rig.dsports.shipments[o["id"]]["shipment"]["estimatedDeliveryDate"] = "2026-09-05T00:00:00Z"
    r = await say(rig, "My order ORD-20260901-000300 has not arrived.")
    await confirm(rig, r)
    case = rig.dsports.support_cases[0]
    assert case["escalation"]["state"] == "NONE"
    e = await say(rig, "I want to speak to a manager about my case", conv=r.conversation_id)
    assert e.requires_confirmation and e.actions[0].tool == "request_case_escalation"
    done = await confirm(rig, e)
    assert case["escalation"]["state"] == "REQUESTED" and case["escalation"]["humanAccepted"] is False
    assert "nobody has taken the case yet" in done.message


async def test_asking_again_after_a_request_is_refused_with_the_true_state():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)                      # created with an escalation request already
    e = await say(rig, "I want to speak to a manager about my case", conv=conv)
    assert not e.requires_confirmation and "already requested" in e.message


async def test_a_resolved_case_cannot_take_a_message_but_can_be_reopened_with_confirmation():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    rig.dsports.staff_resolve(case["id"], "ANSWERED", "Please check with the security desk.")
    m = await say(rig, "Add a message to my case: I checked and it is not there", conv=conv)
    assert not m.requires_confirmation and "resolved" in m.message.lower()
    r = await say(rig, "Reopen my case, it is still not solved", conv=conv)
    assert r.requires_confirmation and r.actions[0].tool == "reopen_support_case"
    done = await confirm(rig, r)
    assert case["status"] == "INVESTIGATING" and "open again" in done.message


async def test_an_open_case_cannot_be_reopened():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    r = await say(rig, "Reopen my case", conv=conv)
    assert not r.requires_confirmation and "not resolved" in r.message


async def test_a_second_complaint_about_the_same_order_does_not_create_a_second_case():
    rig = await support_rig()
    conv, case, _ = await open_case(rig)
    r = await say(rig, "My order says delivered, but I did not receive it.")                  # a brand new conversation, same problem
    done = await confirm(rig, r)
    assert len(rig.dsports.support_cases) == 1 and "already have an open case" in done.message and case["caseNumber"] in done.message


async def test_when_support_is_down_no_case_number_is_ever_invented():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "My order says delivered, but I did not receive it.")
    rig.dsports.support_down = True
    done = await confirm(rig, r)
    assert "CASE-" not in done.message and "didn't go through" in done.message and not rig.dsports.support_cases
    status = await say(rig, "What happened to my complaint?")
    assert "CASE-" not in status.message and status.status in ("error", "ok")


async def test_when_the_order_records_are_unreachable_the_assistant_says_it_could_not_check_and_creates_nothing():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    rig.dsports.down = True
    r = await say(rig, "My order says delivered, but I did not receive it.")
    assert not r.requires_confirmation and not rig.dsports.support_cases
    assert r.status == "error" or "couldn't" in r.message.lower() or "could not" in r.message.lower()
