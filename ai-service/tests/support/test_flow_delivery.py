"""Delivery complaints end to end through the real agent, tools and confirmation flow, against an in-memory DSports with the Java backend's rules."""
from tests.support.conftest_support import BAT_MRF, USER_A, confirm, say, support_rig, tools


async def test_marked_delivered_but_not_received_is_investigated_then_a_real_case_is_created_after_confirmation():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "My order says delivered, but I did not receive it.")
    assert r.intent == "COMPLAINT" and r.status == "ok"
    assert {"get_order", "track_order", "get_payment_status", "search_support_policy"} <= set(tools(r))
    assert r.support["kind"] == "INVESTIGATION" and r.support["complaint"] == "MISSING_DELIVERY"
    verified = [f["statement"] for f in r.support["evidence"]["verified"]]
    assert any("marked delivered" in s for s in verified)                        # a fact that came from the shipment record
    assert any("Proof of delivery" in m["item"] for m in r.support["evidence"]["missing"])
    assert r.support["evidence"]["inferences"] and "not confirmed" in r.message.lower()
    assert r.support["resolution"]["outcome"] == "ESCALATE" and r.support["resolution"]["escalation"]["human_accepted"] is False
    assert r.requires_confirmation and r.actions[0].tool == "create_support_case"
    assert not rig.dsports.support_cases                                          # nothing exists until the customer confirms
    low = r.message.lower()
    assert "i contacted" not in low and "i have contacted" not in low and "warehouse" not in low   # no invented carrier / warehouse action

    done = await confirm(rig, r)
    assert len(rig.dsports.support_cases) == 1
    case = rig.dsports.support_cases[0]
    assert case["category"] == "MISSING_DELIVERY" and case["escalation"]["state"] == "REQUESTED" and case["userId"] == USER_A
    assert case["caseNumber"] in done.message and "created" in done.message.lower()
    assert "nobody has taken" in done.message.lower()                             # a request for a person is not an assigned person
    assert done.support["kind"] == "CASE_CREATED" and done.support["case"]["case_number"] == case["caseNumber"]
    assert case["evidence"] and case["policyCitations"] and case["missingInformation"]


async def test_a_replayed_confirmation_creates_no_second_case():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "My order says delivered, but I did not receive it.")
    await confirm(rig, r)
    again = await confirm(rig, r)
    assert len(rig.dsports.support_cases) == 1
    assert again.error_code == "CONFIRMATION_ALREADY_USED" or "didn't go through" in again.message


async def test_please_raise_it_answers_the_waiting_confirmation():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000123", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "My order says delivered, but I did not receive it.")
    done = await say(rig, "Please raise it", conv=r.conversation_id)
    assert len(rig.dsports.support_cases) == 1 and rig.dsports.support_cases[0]["caseNumber"] in done.message


async def test_a_shipment_still_moving_within_its_estimate_is_answered_without_a_case():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261012-000200", "SHIPPED", [(BAT_MRF, 1)], placed="2026-10-12T08:00:00Z", shipment_status="IN_TRANSIT")
    rig.dsports.shipments[o["id"]]["shipment"]["estimatedDeliveryDate"] = "2099-01-01T00:00:00Z"
    rig.dsports.shipments[o["id"]]["events"][0]["eventTime"] = "2099-01-01T00:00:00Z"        # an event "today": not stalled
    r = await say(rig, "My order has not arrived.")
    assert r.support["resolution"]["outcome"] == "ANSWER" and not r.requires_confirmation and not rig.dsports.support_cases
    assert any(s.label.startswith("Raise a support case") for s in r.suggestions)


async def test_a_stalled_shipment_past_its_estimate_proposes_a_case_without_asking_for_a_person():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20260901-000300", "SHIPPED", [(BAT_MRF, 1)], placed="2026-09-01T08:00:00Z", shipment_status="IN_TRANSIT")
    rig.dsports.shipments[o["id"]]["shipment"]["estimatedDeliveryDate"] = "2026-09-05T00:00:00Z"
    r = await say(rig, "My shipment has not moved for three days.")
    assert r.support["resolution"]["outcome"] == "CREATE_CASE" and r.support["resolution"]["escalation"]["recommended"] is False
    facts = " ".join(f["statement"] for f in r.support["evidence"]["verified"])
    assert "has passed" in facts and "no tracking event" in facts
    done = await confirm(rig, r)
    assert rig.dsports.support_cases[0]["escalation"]["state"] == "NONE" and rig.dsports.support_cases[0]["category"] in ("DELAYED_DELIVERY", "MISSING_DELIVERY")
    assert done.support["case"]["escalation"]["state"] == "NONE"
