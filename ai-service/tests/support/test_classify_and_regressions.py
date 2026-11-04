"""The complaint classifier on the brief's phrases, and regressions found by running the support evaluation on the real stack."""
import pytest

from app.support.classify import classify, safety_flag, wants_human
from app.support.model import Complaint as C
from tests.support.conftest_support import BAT_MRF, USER_A, say, support_rig

BRIEF = [
    ("My order has not arrived.", C.DELAYED_DELIVERY), ("My order says delivered, but I didn't receive it.", C.MISSING_DELIVERY),
    ("My shipment has not moved for three days.", C.DELAYED_DELIVERY), ("I received only one of the two items.", C.PARTIAL_DELIVERY),
    ("The wrong product was delivered.", C.WRONG_PRODUCT), ("The package arrived damaged.", C.DAMAGED_PRODUCT), ("I ordered the wrong size.", C.WRONG_SIZE),
    ("Why is my order delayed?", C.DELAYED_DELIVERY), ("My payment was deducted, but my order failed.", C.PAYMENT_FAILED), ("I was charged twice.", C.DUPLICATE_PAYMENT),
    ("My payment is pending.", C.PAYMENT_PENDING), ("I received an order confirmation, but payment status looks wrong.", C.PAYMENT_PENDING),
    ("My return was approved, but I haven't received the money.", C.REFUND_DELAYED), ("I received only part of my refund.", C.REFUND_PARTIAL),
    ("The bat I received is damaged.", C.DAMAGED_PRODUCT), ("The product specifications do not match what was advertised.", C.PRODUCT_MISMATCH),
    ("The shoes appear defective.", C.PRODUCT_DEFECT), ("The product manual does not explain how to use this item.", C.PRODUCT_MANUAL),
    ("I cannot return my product.", C.RETURN_BLOCKED), ("My return request was rejected.", C.RETURN_REJECTED), ("I want to exchange this item.", C.EXCHANGE_REQUEST),
    ("My pickup did not happen.", C.RETURN_PICKUP), ("I want to raise a complaint.", C.GENERAL), ("Can I speak to support?", C.GENERAL),
]


@pytest.mark.parametrize("text,expected", BRIEF)
def test_every_complaint_of_the_brief_is_recognised(text, expected):
    assert classify(text) is expected


@pytest.mark.parametrize("text", [
    "Where is my order?", "How do I choose the right cricket bat?", "What is your return policy?", "Why hasn't my refund arrived?", "Show my open support cases",
    "What happened to my complaint?", "Can I return my shoes?", "Hi", "I am looking for a cricket bat under 8000", "How does the delivery and return process work?",
])
def test_questions_and_shopping_are_not_complaints(text):
    assert classify(text) is None


@pytest.mark.parametrize("text", [
    "What should I do if the product is defective?", "What happens if a parcel gets lost on the way?", "How do I get a refund if an item arrives damaged?",
    "Can I exchange something if it is the wrong size?",
])
def test_a_hypothetical_policy_question_is_not_a_report_of_a_problem(text):
    assert classify(text) is None


@pytest.mark.parametrize("text,complaint", [("What should I do if my order has not arrived?", C.DELAYED_DELIVERY), ("What if I received the wrong item?", C.WRONG_PRODUCT)])
def test_first_person_experience_keeps_it_a_complaint(text, complaint):
    assert classify(text) is complaint


@pytest.mark.parametrize("text,flag", [
    ("The handle splintered and cut my hand.", True), ("It gave me an electric shock", True), ("It is not safe for kids", True), ("The bat is damaged", False),
    ("My order has not arrived and I am angry", False), ("It caught fire", True), ("Please hurry, this is the worst service ever", False),
])
def test_the_safety_flag_comes_only_from_explicit_words(text, flag):
    assert safety_flag(text) is flag


def test_asking_for_a_person_is_detected():
    assert wants_human("Can I speak to support?") and wants_human("I want to talk to a real person") and not wants_human("Where is my order?")


async def test_my_order_in_a_payment_complaint_does_not_mean_the_latest_order_and_a_customer_without_orders_is_not_asked_for_one():
    rig = await support_rig()
    r = await say(rig, "My payment was deducted, but my order failed.")
    assert r.support["kind"] == "INVESTIGATION" and "No payment attempt" in r.message and "couldn't find that order" not in r.message


async def test_a_double_charge_complaint_finds_the_one_order_with_two_payment_attempts():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000800", "CONFIRMED", [(BAT_MRF, 1)], payment_status=None)
    rig.dsports.add_order(USER_A, "ORD-20260901-000801", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    for ref, st in (("PAY-X1", "CANCELLED"), ("PAY-X2", "SUCCESS")):
        rig.dsports.payments.append({"id": f"pay-{ref}", "paymentReference": ref, "orderId": o["id"], "userId": USER_A, "amount": o["grandTotal"], "currency": "INR", "paymentMethod": "UPI",
                                     "status": st, "paidAt": "2026-10-01T10:00:00Z" if st == "SUCCESS" else None, "createdAt": "2026-10-01T10:00:00Z", "refundedAmount": 0.0})
    r = await say(rig, "I was charged twice.")
    assert r.support["kind"] == "INVESTIGATION" and r.support["order"]["order_number"] == "ORD-20261001-000800" and "Only one payment was captured" in r.message


async def test_every_fact_the_verdict_rests_on_is_shown_even_for_a_long_investigation():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20260901-000300", "SHIPPED", [(BAT_MRF, 1)], placed="2026-09-01T08:00:00Z", shipment_status="IN_TRANSIT")
    rig.dsports.shipments[o["id"]]["shipment"]["estimatedDeliveryDate"] = "2026-09-05T00:00:00Z"
    r = await say(rig, "My shipment has not moved for three days.")
    assert "no tracking event" in r.message and "has passed" in r.message


async def test_an_injury_sentence_makes_the_case_urgent_without_any_safety_keyword():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261010-000770", "DELIVERED", [(BAT_MRF, 1)], shipment_status="DELIVERED")
    r = await say(rig, "The bat I received is damaged and the handle splintered and cut my hand.")
    assert r.support["safety_related"] is True


# ---- found by the first run of the held-out evaluation (2026-10-12); that set is consumed for these items
@pytest.mark.parametrize("text,expected", [
    ("I still haven't gotten my order and it's way past the date you promised.", C.DELAYED_DELIVERY), ("The bat has a crack running along the handle.", C.DAMAGED_PRODUCT),
    ("I got the wrong colour shoes, mine should have been black.", C.WRONG_PRODUCT), ("Something is wrong with my order.", C.GENERAL), ("There is a problem with my payment.", C.GENERAL),
    ("I ordered the wrong size.", C.WRONG_SIZE), ("I need help with my refund", C.GENERAL),
])
def test_phrasings_the_heldout_set_found_are_recognised(text, expected):
    assert classify(text) is expected


@pytest.mark.parametrize("text", ["Where is my order?", "Is my payment pending?", "I want to see my orders", "How do I raise a problem with a product?"])
def test_the_vague_rule_does_not_swallow_ordinary_requests(text):
    assert classify(text) in (None, C.GENERAL) and (text != "Where is my order?" or classify(text) is None)
