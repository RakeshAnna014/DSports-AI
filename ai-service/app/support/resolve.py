"""The deterministic resolution policy.

`decide` is a pure function of the investigation: the same evidence always gives the same outcome and the reasons are recorded. The model
never chooses an outcome. Nothing here refunds, cancels, approves or reverses anything: the strongest thing it can do is PROPOSE a
supported action (which the customer must still confirm and the Java backend still validates) or propose opening a support case.

Escalation (a request for a person to review) is recommended when: the evidence needed to decide is missing or unverifiable, a financial
discrepancy is confirmed by the customer's own records, the customer disputes a decision DSports made, no automated resolution exists, or
a safety concern was reported. Whether anyone has actually taken the case is a separate fact that only the backend can report.
"""
from __future__ import annotations

from app.support.model import CASE_CATEGORY, DELIVERY, Complaint, Decision, Investigation, Outcome

# Lookups whose failure means the answer cannot be trusted. A failed policy / product / review lookup only removes context.
CRITICAL_LOOKUPS = {"your order", "the shipment", "the payment", "your payments", "your refunds", "your returns", "the return eligibility", "your recent orders"}

SUBJECT = {
    Complaint.DELAYED_DELIVERY: "Order delayed or not arrived", Complaint.MISSING_DELIVERY: "Order marked delivered but not received",
    Complaint.PARTIAL_DELIVERY: "Only part of the order received", Complaint.WRONG_PRODUCT: "Wrong product received", Complaint.DAMAGED_PRODUCT: "Product received damaged",
    Complaint.WRONG_SIZE: "Exchange request: wrong size ordered", Complaint.EXCHANGE_REQUEST: "Exchange request", Complaint.PAYMENT_FAILED: "Payment deducted but order failed",
    Complaint.DUPLICATE_PAYMENT: "Charged more than once", Complaint.PAYMENT_PENDING: "Payment pending or status looks wrong", Complaint.REFUND_DELAYED: "Refund not received",
    Complaint.REFUND_PARTIAL: "Refund amount lower than expected", Complaint.RETURN_REJECTED: "Return request rejected", Complaint.RETURN_BLOCKED: "Unable to return a product",
    Complaint.RETURN_PICKUP: "Return pickup did not happen", Complaint.PRODUCT_DEFECT: "Product appears defective", Complaint.PRODUCT_MISMATCH: "Product does not match its description",
    Complaint.PRODUCT_MANUAL: "Product manual or instructions unclear", Complaint.TECHNICAL_ISSUE: "Website or app problem",
    Complaint.GENERAL: "Customer complaint",
}


def case_subject(inv: Investigation) -> str:
    base = SUBJECT[inv.effective]
    return f"{base} - order {inv.order['order_number']}" if inv.order else base


def _d(inv: Investigation, outcome: Outcome, recommendation: str, *, reasons: list[str], escalate: str | None = None, ask: str | None = None,
       actions: list[tuple[str, str, dict]] | None = None) -> Decision:
    if inv.safety_related and outcome in (Outcome.ANSWER, Outcome.SAFE_NEXT_STEP, Outcome.ASK_INFO):
        outcome = Outcome.ESCALATE
        escalate = escalate or "The customer reported a safety concern"
        recommendation = "Because you mentioned a safety concern, please stop using the product until a person has reviewed this. " + recommendation
        reasons = [*reasons, "Safety concern reported: the case needs a person, whatever else was found"]
    if escalate and outcome == Outcome.CREATE_CASE:
        outcome = Outcome.ESCALATE
    return Decision(outcome, CASE_CATEGORY[inv.effective], recommendation, escalate if outcome == Outcome.ESCALATE else None,
                    case_subject(inv) if outcome in (Outcome.CREATE_CASE, Outcome.ESCALATE) else None, ask, actions or [], reasons)


def _return_action(inv: Investigation) -> list[tuple[str, str, dict]]:
    if inv.signals.get("eligible") and inv.order and inv.product and inv.product.get("product_id"):
        return [("Return this item", "RETURN_CHECK", {"order_id": inv.order["order_id"], "product_id": inv.product["product_id"]})]
    return []


def decide(inv: Investigation, *, disputes: bool = False, wants_human: bool = False) -> Decision:
    """The decision for this evidence. A customer who disputes the result (or asks for a person) always gets a case with a review request."""
    d = _decide(inv, wants_human=wants_human)
    if (disputes or wants_human) and d.outcome in (Outcome.ANSWER, Outcome.SAFE_NEXT_STEP, Outcome.CREATE_CASE):
        reason = "The customer asked for a person to review this" if wants_human and not disputes else "The customer disputes the result"
        return Decision(Outcome.ESCALATE, d.category, d.recommendation + " I'll ask for a person to review it.", reason, case_subject(inv), d.ask, d.actions,
                        [*d.reasons, reason])
    return d


def _decide(inv: Investigation, *, wants_human: bool = False) -> Decision:
    s, eff = inv.signals, inv.effective
    critical = [f for f in inv.unverified if f in CRITICAL_LOOKUPS]
    if critical:
        return Decision(Outcome.UNVERIFIABLE, CASE_CATEGORY[eff], "I could not check " + " and ".join(dict.fromkeys(critical)) + " right now, so I won't guess. Nothing was created. Please try again shortly.",
                        None, None, None, [], [f"Lookup failed: {', '.join(critical)}"])

    if eff == Complaint.TECHNICAL_ISSUE:
        return _d(inv, Outcome.CREATE_CASE, "I can't diagnose website or app problems myself, but I can open a support case with what you've told me so a person can look into it.",
                  reasons=["A website or app problem cannot be checked from the customer's records and needs a person"])
    if eff == Complaint.GENERAL:
        if wants_human:
            return _d(inv, Outcome.ESCALATE, "I can't connect you to a person live, but I can open a case and ask for a person to review it.",
                      escalate="The customer asked to speak to a person", reasons=["The customer asked for a person"])
        return _d(inv, Outcome.CREATE_CASE, "I can open a support case with what you've told me so a person can follow up.", reasons=["A general complaint needs a person to follow up"])

    # ---------------------------------------------------------------- delivery
    if eff in DELIVERY:
        if s.get("not_shipped"):
            return _d(inv, Outcome.ANSWER, f"The order has not been shipped because its status is {s.get('order_status')}.", reasons=["Order is not in a shippable state"])
        if s.get("no_shipment"):
            return _d(inv, Outcome.ANSWER, "No shipment exists for this order yet, so there is nothing to track.", reasons=["No shipment record"])
        if eff == Complaint.PARTIAL_DELIVERY:
            if s.get("delivered"):
                return _d(inv, Outcome.ESCALATE, "I can't see which items were in the parcel, so a person needs to check the order against what arrived.",
                          escalate="Part of the order is reported missing and the package contents cannot be verified", reasons=["Delivered shipment, package contents unknown"])
            return _d(inv, Outcome.ANSWER, "The shipment is not marked delivered yet, so the rest of the order may still be on its way.", reasons=["Shipment not delivered"])
        if eff == Complaint.MISSING_DELIVERY:
            return _d(inv, Outcome.ESCALATE, "The shipment is marked delivered but I can't verify where the parcel was left, so a person needs to investigate the delivery.",
                      escalate="Marked delivered but not received; no delivery proof is available to this assistant", reasons=["Delivered per shipment", "No delivery proof available"])
        if s.get("delivery_failed"):
            return _d(inv, Outcome.CREATE_CASE, "The delivery did not complete. A person can arrange the next step.", reasons=["Shipment FAILED or RETURNED"])
        if s.get("eta_passed") or s.get("stalled"):
            why = "past its estimated delivery date" if s.get("eta_passed") else "without tracking updates"
            return _d(inv, Outcome.CREATE_CASE, f"The shipment is {why}. A person can follow it up with the courier.", reasons=["Not delivered", "Past estimate or stalled"])
        return _d(inv, Outcome.ANSWER, "The shipment is moving and is within its estimate; there is nothing to fix yet.", reasons=["In transit, within estimate"])

    # ---------------------------------------------------------------- payments
    if eff in (Complaint.DUPLICATE_PAYMENT, Complaint.PAYMENT_FAILED, Complaint.PAYMENT_PENDING):
        if s.get("duplicate_confirmed"):
            return _d(inv, Outcome.ESCALATE, "Your records show more than one successful payment for this order. A person needs to review it; I can't reverse a payment myself.",
                      escalate="More than one successful payment is recorded for one order", reasons=["Duplicate successful payments confirmed by DSports records"])
        if s.get("payment_order_mismatch"):
            return _d(inv, Outcome.ESCALATE, "A payment is recorded as successful but the order is not confirmed. A person needs to reconcile them.",
                      escalate="A successful payment exists for an order that is not confirmed", reasons=["Payment and order status disagree"])
        if s.get("ambiguous_orders"):
            return _d(inv, Outcome.ASK_INFO, "I can't tell which order you mean.", ask="Which order is this about? Give me the order number and I'll check its payments.", reasons=["Order not identified"])
        if s.get("payments_total", 0) == 0:
            return _d(inv, Outcome.ANSWER, "No payment attempt is recorded for this order.", reasons=["No payment records"])
        if s.get("payment_pending"):
            return _d(inv, Outcome.ANSWER, "A payment for this order is still pending, so its final result has not been reported yet.", reasons=["Payment pending"])
        if s.get("single_capture"):
            return _d(inv, Outcome.ANSWER, "Only one payment was captured for this order. The other debit may not have completed.", reasons=["Single capture recorded"])
        if s.get("no_capture"):
            return _d(inv, Outcome.CREATE_CASE, "I can't see a captured payment. A person can check with the payment provider if you were debited.",
                      reasons=["No successful payment recorded; customer reports a debit that cannot be verified"])
        return _d(inv, Outcome.ANSWER, "The payment records for this order look consistent with its status.", reasons=["Records consistent"])

    # ---------------------------------------------------------------- refunds
    if eff in (Complaint.REFUND_DELAYED, Complaint.REFUND_PARTIAL):
        if s.get("nothing_to_refund"):
            return _d(inv, Outcome.ANSWER, "No return and no refund is recorded for this order, so there is nothing to wait for yet.", reasons=["No return or refund"])
        if s.get("partial_confirmed"):
            return _d(inv, Outcome.ESCALATE, "The amount refunded is lower than the amount paid. A person needs to explain or correct the difference.",
                      escalate="The customer reports a partial refund and the amounts differ", reasons=["Refunded < paid"])
        if s.get("approved_no_refund"):
            return _d(inv, Outcome.ESCALATE, "Your return is approved but no refund has been recorded. A person needs to start it; I can't issue a refund myself.",
                      escalate="Return approved but no refund recorded", reasons=["Return APPROVED, no refund exists"])
        if s.get("refund_failed"):
            return _d(inv, Outcome.ESCALATE, "A refund for this order failed. A person needs to look at it.", escalate="A refund failed", reasons=["Refund FAILED"])
        if s.get("refund_in_progress"):
            return _d(inv, Outcome.ANSWER, "A refund is in progress. I can't give you a delivery date for the money.", reasons=["Refund in progress"])
        if s.get("refund_completed"):
            return _d(inv, Outcome.ANSWER, "DSports shows the refund as completed. When your bank or card shows it is up to the bank, which I can't see.", reasons=["Refund completed"])
        if s.get("return_status") in ("REQUESTED",):
            return _d(inv, Outcome.ANSWER, "Your return has been requested but not decided yet, so no refund can start.", reasons=["Return awaiting decision"])
        if s.get("return_status") == "REJECTED":
            return _d(inv, Outcome.ANSWER, "Your return was rejected, so no refund is due for it.", reasons=["Return REJECTED"])
        return _d(inv, Outcome.CREATE_CASE, "I can't tell from the records why no refund is shown. A person can check.", reasons=["Unclear state"])

    # ---------------------------------------------------------------- returns
    if eff == Complaint.RETURN_REJECTED:
        if s.get("return_status") == "REJECTED":
            return _d(inv, Outcome.ESCALATE, "The return was rejected by DSports. If you disagree, a person can review the decision; I can't overturn it.",
                      escalate="The customer disputes a rejected return", reasons=["Return REJECTED", "Customer disputes it"])
        if s.get("returns_count", 0) == 0:
            return _d(inv, Outcome.ANSWER, "I can't find a return request for this order.", reasons=["No return request"])
        return _d(inv, Outcome.ANSWER, f"The return is {s.get('return_status')}, not rejected.", reasons=["Return not rejected"])
    if eff == Complaint.RETURN_BLOCKED:
        if "eligible" not in s:
            return _d(inv, Outcome.ASK_INFO, "I need to know which product you want to return.", ask="Which product from the order do you want to return?", reasons=["Product not identified"])
        if s["eligible"]:
            return _d(inv, Outcome.SAFE_NEXT_STEP, "DSports' rules say this product can be returned, so I can start the return for you after you confirm.",
                      actions=_return_action(inv), reasons=["Eligible according to DSports"])
        return _d(inv, Outcome.ANSWER, "DSports' return rules say it can't be returned right now. If you think that is wrong, a person can review it.", reasons=["Not eligible according to DSports"])
    if eff == Complaint.RETURN_PICKUP:
        if s.get("returns_count", 0) == 0:
            return _d(inv, Outcome.ANSWER, "I can't find a return request for this order.", reasons=["No return request"])
        return _d(inv, Outcome.CREATE_CASE, "DSports' return record has no pickup details, so a person needs to check what happened.", reasons=["Pickup cannot be verified"])

    # ---------------------------------------------------------------- product line
    if eff in (Complaint.WRONG_SIZE, Complaint.EXCHANGE_REQUEST):
        if s.get("eligible"):
            return _d(inv, Outcome.SAFE_NEXT_STEP, "DSports has no exchange function. You can return this item and order the right size, or I can ask a person about an exchange.",
                      actions=_return_action(inv), reasons=["No exchange API", "Return eligible"])
        return _d(inv, Outcome.CREATE_CASE, "DSports has no exchange function and this item can't be returned right now. A person can look at an exchange request.",
                  reasons=["No exchange API", "Not eligible for return"])
    if eff in (Complaint.DAMAGED_PRODUCT, Complaint.WRONG_PRODUCT, Complaint.PRODUCT_DEFECT, Complaint.PRODUCT_MISMATCH):
        if s.get("no_shipment") or (s.get("delivered") is False):
            return _d(inv, Outcome.ANSWER, "This order is not marked delivered yet, so I can't treat it as received.", reasons=["Order not delivered"])
        word = {Complaint.DAMAGED_PRODUCT: "damage", Complaint.WRONG_PRODUCT: "wrong item", Complaint.PRODUCT_DEFECT: "defect", Complaint.PRODUCT_MISMATCH: "mismatch"}[eff]
        return _d(inv, Outcome.CREATE_CASE, f"A person needs to verify the reported {word}; I can't assess it myself. "
                  + ("The item is also eligible to be returned." if s.get("eligible") else ""), actions=_return_action(inv), reasons=["Needs human verification"])
    if eff == Complaint.PRODUCT_MANUAL:
        if inv.policy:
            return _d(inv, Outcome.ANSWER, "Here is the guidance I could find. If it doesn't help, a person can follow up.", reasons=["Guidance found"])
        return _d(inv, Outcome.CREATE_CASE, "I couldn't find usage guidance for this product. A person can follow up.", reasons=["No guidance found"])
    return _d(inv, Outcome.CREATE_CASE, "A person can look into this.", reasons=["No specific rule"])
