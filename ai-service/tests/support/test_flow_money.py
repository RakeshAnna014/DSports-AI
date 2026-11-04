"""Payment and refund complaints: the customer's claim is never taken as a fact; the records decide."""
import pytest

from tests.support.conftest_support import BAT_MRF, GLOVES, USER_A, confirm, say, support_rig, tools


def add_payment(rig, order, status, ref, amount=None):
    rig.dsports.payments.append({"id": f"pay-{ref}", "paymentReference": ref, "orderId": order["id"], "userId": USER_A, "amount": amount or order["grandTotal"], "currency": "INR",
                                 "paymentMethod": "UPI", "status": status, "paidAt": "2026-10-01T10:00:00Z" if status == "SUCCESS" else None,
                                 "createdAt": "2026-10-01T10:00:00Z", "refundedAmount": 0.0})


async def test_two_successful_payments_for_one_order_are_a_confirmed_duplicate_and_escalate_without_any_reversal():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000500", "CONFIRMED", [(BAT_MRF, 1)], shipment_status=None, payment_status=None)
    add_payment(rig, o, "SUCCESS", "PAY-A")
    add_payment(rig, o, "SUCCESS", "PAY-B")
    r = await say(rig, "I was charged twice for my order ORD-20261001-000500.")
    assert r.support["complaint"] == "DUPLICATE_PAYMENT" and r.support["resolution"]["outcome"] == "ESCALATE"
    assert any("2 payments with a successful status" in f["statement"] for f in r.support["evidence"]["verified"])
    assert r.actions[0].tool == "create_support_case"
    assert not any(t in tools(r) for t in ("create_return", "reorder_product"))                 # no refund / reversal tool exists, and none was used
    assert "refund has been issued" not in r.message.lower() and "reversed" not in r.message.lower()


async def test_a_claimed_double_charge_with_one_captured_payment_is_not_treated_as_a_double_charge():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000501", "CONFIRMED", [(BAT_MRF, 1)], payment_status=None)
    add_payment(rig, o, "SUCCESS", "PAY-A")
    add_payment(rig, o, "FAILED", "PAY-B")
    r = await say(rig, "My payment was deducted twice for ORD-20261001-000501")
    assert r.support["resolution"]["outcome"] == "ANSWER" and not r.requires_confirmation
    assert "Only one payment was captured" in r.message
    assert any("hold" in i["statement"] for i in r.support["evidence"]["inferences"])             # labelled as a possibility, not a fact
    assert any("bank statement" in m["item"] for m in r.support["evidence"]["missing"])


async def test_deducted_but_order_failed_with_no_captured_payment_never_assumes_a_charge_happened():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000502", "CANCELLED", [(BAT_MRF, 1)], payment_status=None)
    add_payment(rig, o, "FAILED", "PAY-F")
    r = await say(rig, "My payment was deducted, but my order failed.")
    assert r.support["complaint"] == "PAYMENT_FAILED"
    facts = " ".join(f["statement"] for f in r.support["evidence"]["verified"])
    assert "No payment with a successful status is recorded" in facts
    assert "you were charged" not in r.message.lower()
    assert r.support["resolution"]["outcome"] == "CREATE_CASE"                                    # a person can ask the payment provider; nothing is reversed here


async def test_a_successful_payment_on_a_cancelled_order_is_a_confirmed_discrepancy():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000503", "CANCELLED", [(BAT_MRF, 1)], payment_status=None)
    add_payment(rig, o, "SUCCESS", "PAY-S")
    r = await say(rig, "My payment was deducted, but my order failed.")
    assert r.support["resolution"]["outcome"] == "ESCALATE"
    assert any("successful payment is recorded, but the order status is CANCELLED" in f["statement"] for f in r.support["evidence"]["verified"])


async def test_a_pending_payment_is_reported_as_pending_and_not_as_failed_or_paid():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20261001-000504", "PENDING_PAYMENT", [(BAT_MRF, 1)], payment_status=None)
    add_payment(rig, o, "PENDING", "PAY-P")
    r = await say(rig, "My payment is pending for ORD-20261001-000504")
    assert r.support["complaint"] == "PAYMENT_PENDING" and r.support["resolution"]["outcome"] == "ANSWER"
    assert "still pending" in r.message


async def test_with_no_payment_records_the_assistant_says_so_instead_of_guessing():
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20261001-000505", "PENDING_PAYMENT", [(BAT_MRF, 1)], payment_status=None)
    r = await say(rig, "My payment was deducted, but my order failed.")
    assert "No payment attempt is recorded" in r.message or "There are no payment attempts" in r.message


async def test_return_approved_but_no_refund_is_escalated_and_the_assistant_cannot_issue_the_refund():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20260920-000600", "DELIVERED", [(BAT_MRF, 1)], placed="2026-09-20T10:00:00Z", shipment_status="DELIVERED")
    rig.dsports.returns.append({"id": "ret-1", "orderId": o["id"], "productId": BAT_MRF, "productName": "MRF Cricket Bat - Virgin", "quantity": 1, "reason": "x",
                                "status": "APPROVED", "key": "k", "userId": USER_A, "createdAt": "2026-09-25T10:00:00Z", "decisionNote": None})
    r = await say(rig, "My return was approved, but I haven't received my refund for ORD-20260920-000600.")
    assert r.support["complaint"] == "REFUND_DELAYED" and r.support["resolution"]["outcome"] == "ESCALATE"
    verified = " ".join(f["statement"] for f in r.support["evidence"]["verified"])
    assert "return is approved, but no refund has been recorded" in verified
    assert "I can't issue a refund myself" in r.message
    assert r.actions[0].tool == "create_support_case"


async def test_a_refund_in_progress_gets_its_real_status_and_no_invented_timeline():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20260920-000601", "DELIVERED", [(BAT_MRF, 1)], placed="2026-09-20T10:00:00Z", shipment_status="DELIVERED")
    add_payment(rig, o, "PARTIALLY_REFUNDED", "PAY-R")
    rig.dsports.refunds.append({"id": "rf-1", "paymentId": "pay-PAY-R", "amount": 3000.0, "status": "PENDING", "reason": "return", "createdAt": "2026-09-26T10:00:00Z", "completedAt": None})
    r = await say(rig, "My return was approved, but I haven't received my refund for ORD-20260920-000601.")
    assert r.support["resolution"]["outcome"] == "ANSWER"
    assert "status PENDING" in " ".join(f["statement"] for f in r.support["evidence"]["verified"])
    assert "I can't give you a delivery date" in r.message
    import re
    assert not re.search(r"\b\d+\s*(business\s*)?(days|hours)\b", r.message.split("From ")[0])   # no timeline of mine; any number of days comes only from the cited policy


async def test_a_partial_refund_is_compared_with_what_was_paid():
    rig = await support_rig()
    o = rig.dsports.add_order(USER_A, "ORD-20260920-000602", "DELIVERED", [(BAT_MRF, 1), (GLOVES, 1)], placed="2026-09-20T10:00:00Z", shipment_status="DELIVERED")
    add_payment(rig, o, "PARTIALLY_REFUNDED", "PAY-Q", amount=10000.0)
    rig.dsports.payments[-1]["refundedAmount"] = 6000.0
    rig.dsports.refunds.append({"id": "rf-2", "paymentId": "pay-PAY-Q", "amount": 6000.0, "status": "COMPLETED", "reason": "return", "createdAt": "2026-09-26T10:00:00Z",
                                "completedAt": "2026-09-28T10:00:00Z"})
    r = await say(rig, "I received only part of my refund for ORD-20260920-000602.")
    assert r.support["complaint"] == "REFUND_PARTIAL" and r.support["resolution"]["outcome"] == "ESCALATE"
    assert any("less than the amount paid" in f["statement"] for f in r.support["evidence"]["verified"])


async def test_the_refund_complaint_phrase_from_phase_one_keeps_its_phase_one_answer():
    """'Why hasn't my refund arrived?' stays the Phase 1 refund explanation; support is an offer on top, not a replacement."""
    rig = await support_rig()
    rig.dsports.add_order(USER_A, "ORD-20260920-000700", "DELIVERED", [(BAT_MRF, 1)], payment_status="SUCCESS", shipment_status="DELIVERED")
    r = await say(rig, "Why hasn't my refund arrived?")
    assert r.intent == "REFUND_WHY"
