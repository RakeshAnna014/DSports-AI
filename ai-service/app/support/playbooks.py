"""Ticket-specific resolution playbooks: what to check, in what order, which backend operation to use, how to verify, and when to escalate.

A playbook is DATA per ticket category (the same values the backend stores). `build_plan` (staff_assist.py) fills it with what the ticket actually holds:
its verified snapshot, the evidence attached to it, live facts the staff member's role is allowed to read, and approved knowledge. The result is never the same
checklist for every ticket, and every explanation of a cause is a labelled HYPOTHESIS: nothing here confirms a root cause or resolves anything.
"""
from __future__ import annotations

from typing import Any

# Operations the console can run for staff, each an existing backend endpoint (nothing here invents one).
OPERATIONS = {
    "ASSIGN_SELF": "POST /api/v1/support/staff/cases/{ref}/assign",
    "SET_STATUS": "POST /api/v1/support/staff/cases/{ref}/status",
    "REPLY_CUSTOMER": "POST /api/v1/support/staff/cases/{ref}/replies",
    "ADD_NOTE": "POST /api/v1/support/staff/cases/{ref}/notes",
    "RESOLVE": "POST /api/v1/support/staff/cases/{ref}/resolve",
}

# The resolution outcomes the backend accepts (ResolutionOutcome). Recording one does not move money or change an order.
OUTCOMES = ["ANSWERED", "REFUND_PROCESSED_VIA_PAYMENT_FLOW", "RETURN_APPROVED_VIA_RETURNS_FLOW", "REPLACEMENT_ARRANGED", "DELIVERY_CONFIRMED", "NOT_ELIGIBLE",
            "NO_ACTION_NEEDED", "CUSTOMER_WITHDREW", "DUPLICATE_OF_ANOTHER_CASE"]

DELIVERY = {"MISSING_DELIVERY", "DELAYED_DELIVERY", "PARTIAL_DELIVERY"}
PAYMENT = {"PAYMENT_ISSUE", "DUPLICATE_PAYMENT"}
MONEY_BACK = {"REFUND_ISSUE", "RETURN_ISSUE", "EXCHANGE_REQUEST"}
PRODUCT = {"DAMAGED_PRODUCT", "WRONG_PRODUCT", "PRODUCT_COMPLAINT"}

# category -> the playbook. `needs` are facts that must be confirmed before the staff member resolves; `escalate` are conditions that call for a more senior person.
PLAYBOOKS: dict[str, dict[str, Any]] = {
    "MISSING_DELIVERY": {
        "title": "Order shown as delivered but not received", "sensitive": False,
        "steps": ["Confirm the order number and delivery address on the ticket match the customer's order.",
                  "Read the shipment's tracking history: when was it marked delivered, by whom, where?",
                  "Ask the customer to check with household members and neighbours, and for any delivery notification they received (only if not already stated).",
                  "Ask the courier partner to confirm the proof of delivery (outside this system: record what they answer in an internal note).",
                  "If delivery cannot be confirmed, decide a replacement or a refund through their own flows (this console only records the outcome)."],
        "tools": ["Order and shipment status (live, ADMIN only)", "Policy: missing delivery procedure (knowledge base)"],
        "needs": ["The shipment record and its delivery event were read.", "The customer's answer on neighbours / household is recorded or was not needed."],
        "escalate": ["The shipment has no delivery event but is marked delivered.", "More than one missing-delivery ticket for the same address.", "High-value order."],
    },
    "DELAYED_DELIVERY": {
        "title": "Order has not arrived yet", "sensitive": False,
        "steps": ["Read the current shipment status and the estimated delivery date.", "Compare it with the status when the ticket was opened: has it moved?",
                  "If it is in transit, reply with the real tracking status and the courier's estimate; do not promise a new date the system does not hold.",
                  "If it has not moved for several days, ask the courier partner for the parcel's location (record the answer in an internal note)."],
        "tools": ["Order and shipment status (live, ADMIN only)", "Policy: shipping and tracking (knowledge base)"],
        "needs": ["The current shipment status was read.", "The customer was told the current status, or the shipment is already delivered."],
        "escalate": ["No movement for the period the shipping policy describes.", "Perishable or time-critical item."],
    },
    "PARTIAL_DELIVERY": {
        "title": "Part of the order arrived", "sensitive": False,
        "steps": ["List the order's items and which ones the customer says are missing.", "Check whether the order was split into several shipments.",
                  "Ask the customer for the packing slip details if the items cannot be identified.", "Arrange the missing items through the shipment or return flows."],
        "tools": ["Order items and shipments (live, ADMIN only)"], "needs": ["The missing items were identified by name or SKU."], "escalate": ["Items are marked delivered but physically missing."],
    },
    "DAMAGED_PRODUCT": {
        "title": "Product arrived damaged", "sensitive": True,
        "steps": ["Confirm the item and order, and whether the order is inside the return window.", "Ask for photos of the item and the packaging (the customer can add them as a message; this system does not store photos).",
                  "Check the damaged-product procedure in the knowledge base.", "Offer the return flow for an eligible order; decide a replacement or refund through their own flows."],
        "tools": ["Return eligibility (customer's Return flow)", "Policy: damaged product procedure (knowledge base)"],
        "needs": ["The customer described the damage or sent details.", "Eligibility under the return policy was checked."], "escalate": ["Safety-related damage.", "Repeated damage on the same product."],
    },
    "WRONG_PRODUCT": {
        "title": "Wrong product received", "sensitive": False,
        "steps": ["Compare the ordered item with what the customer says arrived.", "Check the order lines and the shipment contents recorded at dispatch.",
                  "Offer the return flow and arrange the correct item (replacement) through its own flow."],
        "tools": ["Order items (live, ADMIN only)", "Return eligibility"], "needs": ["The ordered and the received item are both identified."], "escalate": ["Warehouse mix-up suspected for more than one order."],
    },
    "PAYMENT_ISSUE": {
        "title": "Payment problem", "sensitive": True,
        "steps": ["Read the order's payment status and whether the order was confirmed.", "Compare the amount the customer reports with the order total.",
                  "If the payment succeeded but the order failed, check for a technical incident in the same period (see the engineering signal) and escalate it.",
                  "A bank hold from a failed attempt is released by the bank: explain it using the payment failure procedure."],
        "tools": ["Order and payment status (live, ADMIN only)", "Policy: payment failure procedure (knowledge base)", "Engineering signal (derived error groups)"],
        "needs": ["The order's payment status was read.", "Whether money was captured is established from the payment record, not from the customer's statement alone."],
        "escalate": ["Money captured but the order is not confirmed.", "A technical error group coincides with the ticket."],
    },
    "DUPLICATE_PAYMENT": {
        "title": "Charged twice", "sensitive": True,
        "steps": ["Read all payments of the order and their statuses.", "If two captures exist, the refund goes through the refund flow (this console cannot move money).",
                  "If only one capture exists, explain that the second entry is a bank authorisation hold."],
        "tools": ["Payments of the order (live, ADMIN only)", "Refund flow"], "needs": ["The number of captured payments for the order was read."], "escalate": ["Two captures for one order."],
    },
    "REFUND_ISSUE": {
        "title": "Refund not received", "sensitive": True,
        "steps": ["Read the refund request and its status.", "Explain the stage: approval of a return is not the refund; the refund is a separate step and the bank needs time afterwards.",
                  "If the refund is completed, give its reference and date; if not, check why it is stuck and escalate to the finance process."],
        "tools": ["Refund status (live, ADMIN only)", "Policy: refund policy (knowledge base)"], "needs": ["The refund's real status was read."], "escalate": ["Refund approved long ago but still not completed."],
    },
    "RETURN_ISSUE": {
        "title": "Return problem", "sensitive": False,
        "steps": ["Read the return request and its decision note.", "Check eligibility under the return policy (delivered, inside the window, quantity).",
                  "If it was rejected, explain the recorded reason; a person can review a rejected return."],
        "tools": ["Return status and eligibility", "Policy: return policy (knowledge base)"], "needs": ["The return's status and decision note were read."], "escalate": ["The customer disputes a rejection."],
    },
    "EXCHANGE_REQUEST": {
        "title": "Exchange request", "sensitive": False,
        "steps": ["Confirm the order, the item and the size or variant the customer wants.", "There is no exchange flow in the system: arrange it as a return plus a new order and say so plainly.",
                  "Record the agreed route in an internal note."],
        "tools": ["Return eligibility"], "needs": ["The desired replacement item was identified."], "escalate": ["Out-of-stock replacement."],
    },
    "PRODUCT_COMPLAINT": {
        "title": "Product complaint", "sensitive": False,
        "steps": ["Identify the product and the exact complaint.", "Check the product information and warranty documents in the knowledge base.", "Look for the same complaint in other tickets about the same product."],
        "tools": ["Policy: warranty and product documents (knowledge base)"], "needs": ["The product and the complaint are identified."], "escalate": ["Safety concern.", "Several customers report the same defect."],
    },
    "ORDER_ISSUE": {
        "title": "Order problem", "sensitive": False,
        "steps": ["Read the order and its current status.", "Compare the customer's description with the order data (items, totals, address).", "If the order data is correct, explain it; if not, find which step produced the difference."],
        "tools": ["Order status (live, ADMIN only)"], "needs": ["The order was read and compared with the customer's description."], "escalate": ["The order data itself looks wrong."],
    },
    "TECHNICAL_ISSUE": {
        "title": "Website or checkout technical problem", "sensitive": False,
        "steps": ["Ask for what the customer was doing, the page, the time and any message they saw (only what is not already on the ticket).",
                  "Check the engineering signal for a matching error group around that time.", "If a real error group matches, link it in an internal note as a CANDIDATE (not confirmed) and escalate to engineering.",
                  "Do not copy log text or stack traces to the customer."],
        "tools": ["Engineering signal (derived error groups)", "Engineering AI page (ADMIN) for the investigation and a coding prompt"],
        "needs": ["Time and page of the problem are known, or the customer was asked."], "escalate": ["A matching real error group exists.", "The problem blocks purchases."],
    },
    "GENERAL": {
        "title": "General request", "sensitive": False,
        "steps": ["Read the description and decide what the customer needs.", "Answer from the knowledge base if it is a policy question.", "If it is really about an order, payment or product, ask for the order number and re-categorise through an internal note."],
        "tools": ["Policy documents (knowledge base)"], "needs": ["The request is understood."], "escalate": ["The request cannot be classified."],
    },
}

# What an explanation of the cause may say, per category. Each is a HYPOTHESIS and states what would confirm it.
CAUSE_HINTS: dict[str, list[tuple[str, str]]] = {
    "MISSING_DELIVERY": [("The parcel may have been delivered to a different recipient or safe place than the customer expected.", "the courier's proof of delivery"),
                         ("The delivery event may have been recorded by mistake before handover.", "the courier's scan location and time")],
    "DELAYED_DELIVERY": [("The shipment may be waiting at a courier hub or has not been collected yet.", "the latest tracking event and the courier's confirmation")],
    "PAYMENT_ISSUE": [("A payment attempt may have been authorised or held by the bank without being captured.", "the payment record's status and the bank reference"),
                      ("A technical error during checkout may have interrupted the order after the payment step.", "a matching error group around the time, and the order's state")],
    "DUPLICATE_PAYMENT": [("The second entry may be a bank authorisation hold, not a second capture.", "the number of captured payments for the order")],
    "REFUND_ISSUE": [("The refund may be approved but still processing at the bank.", "the refund record's status and completion date")],
    "RETURN_ISSUE": [("The return may be outside the policy window or the quantity may exceed what is eligible.", "the eligibility answer and the decision note")],
    "DAMAGED_PRODUCT": [("The damage may have happened in transit or at packing.", "photos, packaging condition and the courier's notes")],
    "WRONG_PRODUCT": [("The wrong item may have been picked or packed.", "the packing record and the customer's photos")],
    "TECHNICAL_ISSUE": [("A defect or a dependency problem on the site may be behind this.", "a matching error group and a reproduction")],
}
