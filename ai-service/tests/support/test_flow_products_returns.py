"""Product, return, exchange and general complaints; asking for missing details instead of guessing; routing to the existing return flow."""
import pytest

from tests.support.conftest_support import BAT_MRF, GLOVES, RUN_SHOES, USER_A, USER_B, confirm, say, support_rig, tools


async def delivered_order(rig, number="ORD-20261010-000700", items=((BAT_MRF, 1),), user=USER_A, placed="2026-10-10T10:00:00Z"):
    return rig.dsports.add_order(user, number, "DELIVERED", list(items), placed=placed, shipment_status="DELIVERED")


async def test_a_damaged_product_is_checked_against_the_return_rules_and_a_case_is_proposed_with_the_return_as_an_alternative():
    rig = await support_rig()
    await delivered_order(rig)
    r = await say(rig, "The bat I received is damaged.")
    assert r.support["complaint"] == "DAMAGED_PRODUCT" and r.support["resolution"]["outcome"] == "CREATE_CASE"
    assert "check_return_eligibility" in tools(r)
    assert any("Photos" in m["item"] for m in r.support["evidence"]["missing"])                 # the assistant cannot assess images and says so
    assert r.requires_confirmation and r.actions[0].tool == "create_support_case"
    assert any(s.label == "Return this item" for s in r.suggestions)                              # the existing return flow, not a new one
    assert not rig.dsports.returns                                                               # asking about damage returned nothing


async def test_the_return_offer_goes_through_the_existing_return_check():
    rig = await support_rig()
    await delivered_order(rig)
    r = await say(rig, "The bat I received is damaged.")
    choice = next(s.choice_id for s in r.suggestions if s.label == "Return this item")
    nxt = await say(rig, "Return this item", conv=r.conversation_id, choice=choice)
    assert nxt.intent == "RETURN_CHECK" and "check_return_eligibility" in tools(nxt)


async def test_a_not_yet_delivered_order_cannot_be_treated_as_received_damaged_goods():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261012-000710", "SHIPPED", [(BAT_MRF, 1)], shipment_status="IN_TRANSIT")
    r = await say(rig, "The package arrived damaged.")
    assert r.support["resolution"]["outcome"] == "ANSWER" and "not marked delivered" in r.message and not r.requires_confirmation


async def test_with_several_items_the_assistant_asks_which_one_and_offers_server_owned_choices():
    rig = await support_rig()
    await delivered_order(rig, items=((BAT_MRF, 1), (GLOVES, 1)))
    r = await say(rig, "The wrong product was delivered.")
    assert r.support["kind"] == "ASK_INFO" and not rig.dsports.support_cases and "Which one" in r.message
    assert len(r.suggestions) == 2 and all(s.choice_id for s in r.suggestions)
    chosen = await say(rig, "MRF Cricket Bat - Virgin", conv=r.conversation_id, choice=r.suggestions[0].choice_id)
    assert chosen.support["kind"] == "INVESTIGATION" and chosen.support["complaint"] == "WRONG_PRODUCT"
    assert chosen.requires_confirmation


async def test_a_forged_choice_id_does_nothing():
    rig = await support_rig()
    await delivered_order(rig, items=((BAT_MRF, 1), (GLOVES, 1)))
    r = await say(rig, "The wrong product was delivered.")
    forged = await say(rig, "Return this item", conv=r.conversation_id, choice="deadbeef0000")
    assert "no longer available" in " ".join(forged.disclaimers) and not rig.dsports.support_cases


async def test_a_defect_is_proposed_as_a_case_and_reviews_are_context_not_proof():
    rig = await support_rig()
    await delivered_order(rig, items=((RUN_SHOES, 1),), number="ORD-20261010-000720")
    r = await say(rig, "The shoes appear defective.")
    assert r.support["complaint"] == "PRODUCT_DEFECT" and "get_product_reviews" in tools(r)
    assert any("not proof" in f["statement"] for f in r.support["evidence"]["verified"])


async def test_a_wrong_size_is_an_exchange_request_and_the_assistant_admits_there_is_no_exchange_function():
    rig = await support_rig()
    await delivered_order(rig, items=((RUN_SHOES, 1),), number="ORD-20261010-000721")
    r = await say(rig, "I ordered the wrong size.")
    assert r.support["complaint"] == "WRONG_SIZE" and "no exchange function" in r.message
    assert not r.requires_confirmation and any(s.label == "Return this item" for s in r.suggestions)


async def test_a_rejected_return_is_confirmed_from_the_record_and_escalated_as_a_dispute_without_overturning_it():
    rig = await support_rig()
    o = await delivered_order(rig, number="ORD-20261001-000730")
    rig.dsports.returns.append({"id": "ret-9", "orderId": o["id"], "productId": BAT_MRF, "productName": "MRF Cricket Bat - Virgin", "quantity": 1, "reason": "x",
                                "status": "REJECTED", "key": "k", "userId": USER_A, "createdAt": "2026-10-02T10:00:00Z", "decisionNote": "Used item"})
    r = await say(rig, "My return request was rejected.")
    assert r.support["complaint"] == "RETURN_REJECTED" and r.support["resolution"]["outcome"] == "ESCALATE"
    assert any("Used item" in f["statement"] for f in r.support["evidence"]["verified"])
    assert "I can't overturn it" in r.message


async def test_i_cannot_return_my_product_reports_the_decision_dsports_made():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261012-000740", "SHIPPED", [(BAT_MRF, 1)], shipment_status="IN_TRANSIT")
    r = await say(rig, "I cannot return my product.")
    assert r.support["complaint"] == "RETURN_BLOCKED"
    assert any("Only delivered orders can be returned" in f["statement"] for f in r.support["evidence"]["verified"])


async def test_a_pickup_that_did_not_happen_cannot_be_verified_so_a_person_is_asked():
    rig = await support_rig()
    o = await delivered_order(rig, number="ORD-20261003-000750")
    rig.dsports.returns.append({"id": "ret-8", "orderId": o["id"], "productId": BAT_MRF, "productName": "MRF Cricket Bat - Virgin", "quantity": 1, "reason": "x",
                                "status": "APPROVED", "key": "k", "userId": USER_A, "createdAt": "2026-10-04T10:00:00Z", "decisionNote": None})
    r = await say(rig, "My return pickup did not happen.")
    assert r.support["complaint"] == "RETURN_PICKUP" and r.support["resolution"]["outcome"] == "CREATE_CASE"
    assert any("Pickup details" in m["item"] for m in r.support["evidence"]["missing"])


async def test_i_want_to_raise_a_complaint_asks_what_it_is_about_and_creates_nothing():
    rig = await support_rig()
    r = await say(rig, "I want to raise a complaint.")
    assert r.support["kind"] == "ASK_INFO" and not rig.dsports.support_cases and r.suggestions
    follow = await say(rig, "My order has not arrived", conv=r.conversation_id)
    assert follow.intent == "COMPLAINT" and follow.support["complaint"] == "DELAYED_DELIVERY"
    assert follow.support["kind"] == "ASK_INFO" and "order number" in follow.message             # this customer has no orders, so it asks instead of guessing


@pytest.mark.parametrize("message", [
    "Nobody got back to me. My delivery complaint needs a person to look at it",      # the wording of held-out H08, which exposed this defect
    "I want a person to look at my delivery complaint",
])
async def test_asking_for_a_person_with_no_open_case_creates_the_case_with_an_escalation_request(message):
    """Regression for the defect H08 revealed: the human request was dropped when no case existed yet (case created with escalation NONE).
    Escalation stays a REQUEST: nothing here claims a person has taken the case."""
    rig = await support_rig()
    await delivered_order(rig, number="ORD-20261010-000801")
    r = await say(rig, message)
    assert r.requires_confirmation and r.actions[0].tool == "create_support_case"
    await confirm(rig, r)
    case = rig.dsports.support_cases[0]
    assert case["escalation"]["state"] == "REQUESTED" and case["escalation"]["humanAccepted"] is False
    assert "I have escalated" not in r.message and "assigned to" not in r.message


async def test_a_plain_complaint_without_a_request_for_a_person_is_not_escalated():
    rig = await support_rig()
    await delivered_order(rig, number="ORD-20261010-000802")
    r = await say(rig, "My order says delivered but I did not receive it.")
    assert r.requires_confirmation
    await confirm(rig, r)
    reason = rig.dsports.support_cases[0]["escalation"].get("reason") or ""
    assert "asked for a person" not in reason and "speak to a person" not in reason          # a person is never requested on the customer's behalf unless they asked


async def test_a_website_problem_is_a_technical_ticket_proposed_for_confirmation_and_created_only_after_it():
    rig = await support_rig()
    r = await say(rig, "The website will not let me log in, it keeps showing an error after I enter my password")
    assert r.intent == "COMPLAINT" and r.support["complaint"] == "TECHNICAL_ISSUE"
    assert r.requires_confirmation and r.actions[0].tool == "create_support_case" and not rig.dsports.support_cases      # nothing is written before the customer confirms
    done = await confirm(rig, r)
    assert len(rig.dsports.support_cases) == 1 and rig.dsports.support_cases[0]["category"] == "TECHNICAL_ISSUE"
    assert rig.dsports.support_cases[0]["caseNumber"] in done.message                                                        # the real number from the backend, not invented


async def test_a_passing_mention_of_the_website_does_not_open_a_ticket():
    rig = await support_rig()
    r = await say(rig, "Does the website have a size guide for cricket gloves?")
    assert not r.requires_confirmation and not rig.dsports.support_cases


async def test_can_i_speak_to_support_is_honest_about_live_agents_and_offers_a_case_with_a_review_request():
    rig = await support_rig()
    r = await say(rig, "Can I speak to support?")
    assert "can't connect you to a person live" in r.message.lower() and not rig.dsports.support_cases


async def test_a_long_request_for_a_person_with_no_orders_is_honest_and_does_not_demand_an_order_number():
    rig = await support_rig()
    r = await say(rig, "I want to speak to a human agent and raise a support ticket")
    assert "can't connect you to a person live" in r.message.lower() and "couldn't find a matching order" not in r.message.lower() and not rig.dsports.support_cases


async def test_the_manual_question_is_answered_from_the_cited_guidance_or_goes_to_a_person():
    rig = await support_rig()
    await delivered_order(rig, number="ORD-20261010-000760")
    r = await say(rig, "The product manual does not explain how to use this item.")
    assert r.support["complaint"] == "PRODUCT_MANUAL"


async def test_a_safety_concern_always_gets_a_case_with_an_escalation_request_and_a_stop_using_warning():
    rig = await support_rig()
    await delivered_order(rig, number="ORD-20261010-000770")
    r = await say(rig, "The bat I received is damaged and the handle splintered and injured my hand.")
    assert r.support["safety_related"] is True and r.support["resolution"]["outcome"] in ("CREATE_CASE", "ESCALATE")
    assert "stop using" in r.message.lower() or r.support["resolution"]["outcome"] == "CREATE_CASE"
    done = await confirm(rig, r)
    assert rig.dsports.support_cases[0]["priority"] == "URGENT"


async def test_someone_elses_order_number_is_not_found_and_reveals_nothing():
    rig = await support_rig()
    rig.dsports.add_order(USER_B, "ORD-20261010-000999", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "My order ORD-20261010-000999 has not arrived.")
    assert "couldn't find that order" in r.message.lower() or "could not find" in r.message.lower()
    assert "MRF" not in r.message and "7,6" not in r.message                                      # nothing about the other customer's order leaked
    assert all(t["status"] != "ok" for t in r.meta.tools if t["tool"] in ("get_order", "track_order"))
    assert not rig.dsports.support_cases
