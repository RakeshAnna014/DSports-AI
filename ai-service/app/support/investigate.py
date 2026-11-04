"""Evidence gathering and assessment for one complaint.

GATHER  READ-only tool calls through the same typed registry (customer's own token), run in parallel, bounded by the request's tool
        budget. A failed lookup is recorded as UNVERIFIED; it is never replaced by a guess.
ASSESS  Pure functions over the tool results. They produce facts (each with its source), inferences (always possibilities, with their
        basis) and missing information, plus machine-readable `signals` that the decision engine reads. No model is involved.
"""
from __future__ import annotations

import asyncio
import datetime as dt
import re
from dataclasses import dataclass
from typing import Any

from app.agent import templates
from app.agent.runtime import Runtime
from app.support.model import (DELIVERY, NEEDS_PRODUCT, PAYMENT, PRODUCT_LINE, REFUND, RETURNS, Complaint, Fact, Inference, Investigation, Missing)
from app.tools.account_tools import parse_time

SUCCESS = {"SUCCESS", "PARTIALLY_REFUNDED", "REFUNDED"}
PENDING = {"CREATED", "PENDING", "PROCESSING", "AUTHORIZED"}
NOT_CONFIRMED_ORDER = {"PENDING_PAYMENT", "CANCELLED", "FAILED"}
TERMINAL_SHIPMENT = {"DELIVERED", "RETURNED", "CANCELLED"}

POLICY_QUESTION: dict[Complaint, str] = {
    Complaint.DELAYED_DELIVERY: "What happens if my order is delayed or has not arrived?",
    Complaint.MISSING_DELIVERY: "What should I do if my order is marked delivered but I did not receive it?",
    Complaint.PARTIAL_DELIVERY: "What should I do if I received only part of my order?",
    Complaint.DAMAGED_PRODUCT: "What should I do if my product arrived damaged?",
    Complaint.WRONG_PRODUCT: "What should I do if I received the wrong product?",
    Complaint.WRONG_SIZE: "Can I exchange a product or change the size I ordered?",
    Complaint.EXCHANGE_REQUEST: "Can I exchange a product or change the size I ordered?",
    Complaint.PAYMENT_FAILED: "What should I do if my payment was deducted but my order failed?",
    Complaint.DUPLICATE_PAYMENT: "What should I do if I was charged twice for an order?",
    Complaint.PAYMENT_PENDING: "What does a pending payment mean and what should I do?",
    Complaint.REFUND_DELAYED: "How do refunds work and how long can a refund take after a return?",
    Complaint.REFUND_PARTIAL: "How are refund amounts calculated and why could a refund be partial?",
    Complaint.RETURN_REJECTED: "Why can a return request be rejected and what can I do about it?",
    Complaint.RETURN_BLOCKED: "What is the return policy and who can return a product?",
    Complaint.RETURN_PICKUP: "How do returns work and what happens after a return is approved?",
    Complaint.PRODUCT_DEFECT: "What is the warranty and what should I do if my product is defective?",
    Complaint.PRODUCT_MISMATCH: "What should I do if a product does not match its description?",
    Complaint.PRODUCT_MANUAL: "How do I use and care for my product?",
    Complaint.TECHNICAL_ISSUE: "How does DSports support handle complaints and what can I expect?",
    Complaint.GENERAL: "How does DSports support handle complaints and what can I expect?",
}


@dataclass
class Request:
    """What the customer asked about. Ids come from the customer's own records (resolved by the flow), never from the model."""
    complaint: Complaint
    description: str
    order_id: str | None = None
    product_id: str | None = None
    product_name: str | None = None
    question: str | None = None              # the customer's own question (product manual / general)
    order_data: dict[str, Any] | None = None  # the order as already read by the flow (get_order result), so it is not fetched twice
    safety_related: bool = False


class Gatherer:
    """Runs tool calls and remembers which ones failed."""

    def __init__(self, rt: Runtime, inv: Investigation):
        self.rt, self.inv = rt, inv

    async def call(self, tool: str, args: dict[str, Any], label: str) -> Any | None:
        result = await self.rt.call(tool, args)
        if result.ok:
            return result.data
        self.inv.tool_failures.append(f"{label}: {result.error_message}")
        self.inv.unverified.append(label)
        return None


def _days_between(a: dt.datetime | None, b: dt.datetime) -> int | None:
    return None if a is None else max(0, (b - a).days)


def _money(amount: Any, currency: str | None = "INR") -> str:
    try:
        return templates.inr(float(amount))
    except (TypeError, ValueError):
        return str(amount)


# ---------------------------------------------------------------------------------------------------- main entry

async def investigate(rt: Runtime, req: Request, now: dt.datetime | None = None) -> Investigation:
    now = now or dt.datetime.now(dt.timezone.utc)
    inv = Investigation(complaint=req.complaint, effective=req.complaint, safety_related=req.safety_related)
    g = Gatherer(rt, inv)
    c = req.complaint
    if req.product_id:
        inv.product = {"product_id": req.product_id, "name": req.product_name}

    order = req.order_data or (await g.call("get_order", {"order_id": req.order_id}, "your order") if req.order_id else None)
    if order:
        inv.order = {"order_id": order["order_id"], "order_number": order["order_number"], "status": order["status"], "total": order.get("total"),
                     "currency": order.get("currency", "INR"), "placed_at": order.get("placed_at"),
                     "items": [{"product_id": i["product_id"], "name": i["name"], "quantity": i["quantity"]} for i in order.get("items", [])]}
        inv.add(Fact("ORDER", f"Order {order['order_number']} was placed on {templates.when(order.get('placed_at'))} and its status is {order['status']}; "
                              f"total {_money(order.get('total'))}.", order["order_number"]))
        if req.product_id:
            line = next((i for i in inv.order["items"] if i["product_id"] == req.product_id), None)
            if line:
                inv.product = {"product_id": line["product_id"], "name": line["name"], "quantity": line["quantity"]}
                inv.add(Fact("ORDER", f"The order contains {line['quantity']} x {line['name']}.", order["order_number"]))

    oid = inv.order["order_id"] if inv.order else None
    # ---- gather the evidence this complaint needs, in parallel
    jobs: dict[str, Any] = {}
    if c in DELIVERY and oid:
        jobs["tracking"] = g.call("track_order", {"order_id": oid}, "the shipment")
        jobs["payments"] = g.call("get_payment_status", {"order_id": oid}, "the payment")
    elif c in PAYMENT:
        jobs["payments"] = g.call("get_payment_status", {"order_id": oid} if oid else {}, "your payments")
        if not oid:
            jobs["orders"] = g.call("get_orders", {"limit": 5, "include_items": False}, "your recent orders")
        else:
            jobs["tracking"] = g.call("track_order", {"order_id": oid}, "the shipment") if c == Complaint.PAYMENT_PENDING else asyncio.sleep(0)
    elif c in REFUND and oid:
        jobs["refunds"] = g.call("get_refund_status", {"order_id": oid}, "your refunds")
        jobs["returns"] = g.call("get_return_status", {"order_id": oid}, "your returns")
        jobs["payments"] = g.call("get_payment_status", {"order_id": oid}, "the payment")
    elif c in RETURNS and oid:
        jobs["returns"] = g.call("get_return_status", {"order_id": oid}, "your returns")
        if c == Complaint.RETURN_BLOCKED and req.product_id:
            jobs["eligibility"] = g.call("check_return_eligibility", {"order_id": oid, "product_id": req.product_id}, "the return eligibility")
    elif c in PRODUCT_LINE and oid:
        jobs["tracking"] = g.call("track_order", {"order_id": oid}, "the shipment")
        if req.product_id:
            jobs["eligibility"] = g.call("check_return_eligibility", {"order_id": oid, "product_id": req.product_id}, "the return eligibility")
            if c in (Complaint.PRODUCT_DEFECT, Complaint.PRODUCT_MISMATCH):
                jobs["product"] = g.call("get_product", {"product_id": req.product_id}, "the product details")
                jobs["reviews"] = g.call("get_product_reviews", {"product_id": req.product_id, "limit": 3}, "the product reviews")
    jobs["policy"] = g.call("search_support_policy", {"question": req.question or POLICY_QUESTION[c], "limit": 3}, "the support policy")
    names = list(jobs)
    results = dict(zip(names, await asyncio.gather(*jobs.values())))

    # ---- assess
    if c in DELIVERY:
        _delivery(rt, inv, results.get("tracking"), results.get("payments"), now)
    elif c in PAYMENT:
        _payment(inv, results.get("payments"), results.get("orders"), results.get("tracking"), now)
    elif c in REFUND:
        _refund(inv, results.get("refunds"), results.get("returns"), results.get("payments"))
    elif c in RETURNS:
        _returns(inv, results.get("returns"), results.get("eligibility"))
    elif c in PRODUCT_LINE:
        _product_line(inv, results.get("tracking"), results.get("eligibility"), results.get("product"), results.get("reviews"), req)
    _policy(inv, results.get("policy"), req.question or POLICY_QUESTION[c])
    if req.safety_related:
        inv.add(Fact("CUSTOMER_STATEMENT", "You mentioned a safety concern.", None, "CUSTOMER"))
    return inv


_WORDS = re.compile(r"[a-z0-9]{3,}")


def best_passage(text: str, question: str, limit: int = 400) -> str:
    """The paragraph of a cited chunk that best matches the question, without markdown headings or the sample-document banner."""
    wanted = set(_WORDS.findall(question.lower()))
    heading, candidates = "", []                                     # (score, order, paragraph): a section's heading counts toward its paragraphs
    for i, raw in enumerate(re.split(r"\n\s*\n", text)):
        p = " ".join(raw.split())
        if not p or p.startswith((">", "|", "---")):
            continue
        if p.startswith("#"):
            heading = p.lstrip("# ").strip()
            continue
        candidates.append((len(wanted & set(_WORDS.findall(f"{heading} {p}".lower()))), -i, p))
    chosen = max(candidates)[2] if candidates else " ".join(text.split())
    return chosen if len(chosen) <= limit else chosen[: limit - 3].rstrip() + "..."


def _policy(inv: Investigation, data: dict | None, question: str = "") -> None:
    if data is None:
        return
    chunks = data.get("chunks") or []
    if not chunks:
        inv.missing.append(Missing("An applicable support policy", "No matching policy document was found, so I won't describe a procedure I can't cite"))
        return
    for ch in chunks[:2]:
        inv.policy.append({"source": ch["source"], "title": ch.get("title") or ch["source"], "chunk_id": ch["chunk_id"], "document_type": ch.get("document_type"),
                           "snippet": best_passage(ch["text"], question)})
    inv.add(Fact("POLICY", f"Applicable guidance: {inv.policy[0]['title']}.", inv.policy[0]["source"], "POLICY"))


# ---------------------------------------------------------------------------------------------------- delivery

def _delivery(rt: Runtime, inv: Investigation, tracking: dict | None, payments: dict | None, now: dt.datetime) -> None:
    order = inv.order or {}
    s = inv.signals
    s["order_status"] = order.get("status")
    if payments is not None:
        _payment_facts(inv, payments.get("payments", []))
    if tracking is None:
        return
    sh = tracking.get("shipment")
    if order.get("status") in NOT_CONFIRMED_ORDER:
        s["not_shipped"] = True
        inv.add(Fact("ORDER", f"The order status is {order['status']}, so it has not been shipped.", order.get("order_number")))
        return
    if sh is None:
        s["no_shipment"] = True
        inv.add(Fact("SHIPMENT", "No shipment has been created for this order yet.", order.get("order_number")))
        if order.get("status") in ("CONFIRMED", "PROCESSING", "PAID"):
            inv.inferences.append(Inference("The order may still be being prepared for dispatch.", "The order is confirmed but has no shipment record"))
        return
    status = sh.get("status")
    s["shipment_status"] = status
    ref = sh.get("tracking_number")
    inv.add(Fact("SHIPMENT", f"The shipment {ref} ({sh.get('courier') or 'courier not recorded'}) has status {status}.", ref))
    if sh.get("shipped_at"):
        inv.add(Fact("SHIPMENT", f"It was shipped on {templates.when(sh['shipped_at'])}.", ref))
    events = sh.get("events") or []
    last = max(events, key=lambda e: e.get("time") or "", default=None)
    last_time = parse_time(last.get("time")) if last else None
    if last:
        inv.add(Fact("SHIPMENT", f"The latest tracking event is \"{last.get('description') or last.get('status')}\" on {templates.when(last.get('time'))}.", ref))
    est = parse_time(sh.get("estimated_delivery"))
    if est:
        inv.add(Fact("SHIPMENT", f"The estimated delivery date is {templates.when(sh['estimated_delivery'])}.", ref))
    delivered = status == "DELIVERED"
    s["delivered"] = delivered
    if not delivered:
        stalled = _days_between(last_time, now)
        s["days_since_last_event"] = stalled
        s["eta_passed"] = bool(est and est < now)
        stall_days = rt.settings.support_stall_days
        s["stalled"] = bool(stalled is not None and stalled >= stall_days and status not in TERMINAL_SHIPMENT)
        if s["eta_passed"]:
            inv.add(Fact("SHIPMENT", f"The estimated delivery date ({templates.when(sh['estimated_delivery'])}) has passed and the shipment is not marked delivered.", ref))
        if s["stalled"]:
            inv.add(Fact("SHIPMENT", f"There has been no tracking event for {stalled} days (this assistant treats {stall_days} or more days as stalled; "
                                     f"that is a demo threshold, not a DSports delivery commitment).", ref))
        if status in ("FAILED", "RETURNED"):
            s["delivery_failed"] = True
            inv.add(Fact("SHIPMENT", f"The shipment status is {status}, which means the delivery did not complete.", ref))
        elif s["eta_passed"] or s["stalled"]:
            inv.inferences.append(Inference("The parcel may be delayed with the courier.", "The shipment is not delivered and it is past its estimate or has had no movement"))
        return
    # the shipment says DELIVERED
    s["delivered_at"] = sh.get("delivered_at")
    if inv.effective in (Complaint.DELAYED_DELIVERY,):
        inv.effective = Complaint.MISSING_DELIVERY          # "not arrived" + "marked delivered" is a missing-delivery dispute
    if sh.get("delivered_at"):
        inv.add(Fact("SHIPMENT", f"The shipment is marked delivered on {templates.when(sh['delivered_at'])}"
                                 + (f" in {sh['delivery_city']}" if sh.get("delivery_city") else "") + ".", ref))
    else:
        inv.add(Fact("SHIPMENT", "The shipment is marked delivered but no delivery timestamp is recorded.", ref))
    s["delivery_proof_available"] = False                  # the shipment API exposes no signature, photo or handover record
    inv.missing.append(Missing("Proof of delivery (signature, photo or handover record)",
                               "The records I can read do not include any, so I cannot verify where the parcel was left"))
    inv.inferences.append(Inference("The parcel may have been left with a neighbour or at a safe place, or marked delivered by mistake.",
                                    "The shipment says delivered but there is no delivery proof"))


# ---------------------------------------------------------------------------------------------------- payments

def _payment_facts(inv: Investigation, rows: list[dict]) -> None:
    for p in rows[:5]:
        when = f" (paid on {templates.when(p['paid_at'])})" if p.get("paid_at") else ""
        inv.add(Fact("PAYMENT", f"Payment {p.get('reference')}: {_money(p.get('amount'))} by {p.get('method') or 'unknown method'}, status {p.get('status')}{when}.", p.get("reference")))
    s = inv.signals
    s["payments_total"] = len(rows)
    s["payments_success"] = sum(1 for p in rows if p.get("status") in SUCCESS)
    s["payments_pending"] = sum(1 for p in rows if p.get("status") in PENDING)
    s["payments_failed"] = sum(1 for p in rows if p.get("status") in ("FAILED", "CANCELLED"))
    s["payments_success_amount"] = round(sum(float(p.get("amount") or 0) for p in rows if p.get("status") in SUCCESS), 2)


def _payment(inv: Investigation, payments: dict | None, orders: dict | None, tracking: dict | None, now: dt.datetime) -> None:
    s = inv.signals
    if payments is None:
        return
    rows = payments.get("payments", [])
    order = inv.order
    if order is None and orders is not None:
        # no order named: look across the most recent orders for a payment/order mismatch, without guessing which order the customer means
        by_order = {o["order_id"]: o for o in orders.get("orders", [])}
        mismatches = [p for p in rows if p.get("status") in SUCCESS and (by_order.get(p.get("order_id")) or {}).get("status") in NOT_CONFIRMED_ORDER]
        dup_orders = {}
        for p in rows:
            if p.get("status") in SUCCESS:
                dup_orders.setdefault(p.get("order_id"), []).append(p)
        dups = {k: v for k, v in dup_orders.items() if len(v) >= 2}
        def adopt(oid: str) -> list[dict]:
            row = by_order[oid]
            inv.order = {"order_id": oid, "order_number": row["order_number"], "status": row["status"], "total": row.get("total"), "currency": row.get("currency", "INR"),
                         "placed_at": row.get("placed_at"), "items": []}
            return [x for x in rows if x.get("order_id") == oid]
        attempts: dict[str, int] = {}
        for p in rows:
            attempts[p.get("order_id")] = attempts.get(p.get("order_id"), 0) + 1
        several = [oid for oid, n in attempts.items() if n >= 2 and oid in by_order]
        unsettled = [oid for oid in by_order if by_order[oid]["status"] in NOT_CONFIRMED_ORDER or any(p.get("order_id") == oid and p.get("status") not in SUCCESS for p in rows)]
        if inv.complaint == Complaint.DUPLICATE_PAYMENT and len(dups) == 1 and next(iter(dups)) in by_order:
            rows = adopt(next(iter(dups)))
        elif inv.complaint == Complaint.DUPLICATE_PAYMENT and len(several) == 1:
            rows = adopt(several[0])                         # the one order that really has more than one payment attempt
        elif inv.complaint in (Complaint.PAYMENT_FAILED, Complaint.PAYMENT_PENDING) and len(mismatches) == 1 and mismatches[0].get("order_id") in by_order:
            rows = adopt(mismatches[0]["order_id"])
        elif inv.complaint in (Complaint.PAYMENT_FAILED, Complaint.PAYMENT_PENDING) and len(unsettled) == 1:
            rows = adopt(unsettled[0])                       # exactly one order whose payment or status is not settled
        elif len(by_order) == 1 and rows:
            rows = adopt(next(iter(by_order)))
        elif not rows:
            inv.add(Fact("PAYMENT", "There are no payment attempts on your account.", None))
        else:
            s["ambiguous_orders"] = [o["order_number"] for o in orders.get("orders", [])][:5]
    _payment_facts(inv, rows)
    order = inv.order
    if order:
        s["order_status"] = order["status"]
        inv.add(Fact("ORDER", f"Order {order['order_number']} has status {order['status']}.", order["order_number"]))
    success, pending, failed = s.get("payments_success", 0), s.get("payments_pending", 0), s.get("payments_failed", 0)
    # ---- what the records establish
    if success >= 2:
        s["duplicate_confirmed"] = True
        inv.add(Fact("PAYMENT", f"{success} payments with a successful status are recorded for the same order, totalling {_money(s['payments_success_amount'])}.",
                     order["order_number"] if order else None))
    if success >= 1 and order and order["status"] in NOT_CONFIRMED_ORDER:
        s["payment_order_mismatch"] = True
        inv.add(Fact("PAYMENT", f"A successful payment is recorded, but the order status is {order['status']}.", order["order_number"]))
    if success == 0 and rows:
        s["no_capture"] = True
        inv.add(Fact("PAYMENT", "No payment with a successful status is recorded for this order.", order["order_number"] if order else None))
        if inv.complaint in (Complaint.PAYMENT_FAILED, Complaint.DUPLICATE_PAYMENT):
            inv.inferences.append(Inference("An amount shown by your bank may be a temporary hold from a failed or cancelled attempt that is later released by the bank.",
                                            "The payment attempts recorded by DSports did not succeed"))
            inv.missing.append(Missing("The debit entry from your bank statement (date, amount, reference)", "DSports can only see its own payment records, not your bank's"))
    if success == 1 and failed + pending >= 1 and inv.complaint == Complaint.DUPLICATE_PAYMENT:
        s["single_capture"] = True
        inv.inferences.append(Inference("The other debit may be a hold from an attempt that did not complete.", "Only one successful payment is recorded"))
        inv.missing.append(Missing("The two debit entries from your bank statement", "DSports can only see its own payment records"))
    if pending:
        s["payment_pending"] = True
        inv.add(Fact("PAYMENT", f"{pending} payment attempt(s) are still in a pending/processing state.", order["order_number"] if order else None))
        inv.inferences.append(Inference("The payment gateway may not have reported the final result yet.", "The payment status is pending or processing"))
    if not rows and "orders" not in s and order is not None:
        inv.add(Fact("PAYMENT", "No payment attempt is recorded for this order.", order["order_number"]))


# ---------------------------------------------------------------------------------------------------- refunds and returns

def _return_facts(inv: Investigation, returns: dict | None) -> list[dict]:
    rows = (returns or {}).get("returns", [])
    for r in rows[:3]:
        note = f" DSports' note: \"{r['decision_note']}\"." if r.get("decision_note") else ""
        inv.add(Fact("RETURN", f"The return of {r.get('quantity')} x {r.get('product_name')} requested on {templates.when(r.get('requested_at'))} has status {r.get('status')}.{note}",
                     r.get("return_id")))
    inv.signals["return_status"] = rows[0]["status"] if rows else None
    inv.signals["returns_count"] = len(rows)
    return rows


def _refund(inv: Investigation, refunds: dict | None, returns: dict | None, payments: dict | None) -> None:
    s = inv.signals
    rows = _return_facts(inv, returns)
    if payments is not None:
        _payment_facts(inv, payments.get("payments", []))
    paid = refunded = 0.0
    in_progress = completed = failed = 0
    if refunds is not None:
        for p in refunds.get("payments", []):
            paid += float(p.get("paid_amount") or 0)
            refunded += float(p.get("refunded_amount") or 0)
            for r in p.get("refunds", []):
                inv.add(Fact("REFUND", f"A refund of {_money(r.get('amount'))} requested on {templates.when(r.get('requested_at'))} has status {r.get('status')}"
                                       + (f" (completed {templates.when(r['completed_at'])})" if r.get("completed_at") else "") + ".", r.get("refund_id")))
                st = r.get("status")
                if st == "COMPLETED":
                    completed += 1
                elif st in ("FAILED", "REJECTED", "CANCELLED"):
                    failed += 1
                else:
                    in_progress += 1
        s.update({"refund_paid": round(paid, 2), "refund_refunded": round(refunded, 2), "refunds_completed": completed, "refunds_in_progress": in_progress,
                  "refunds_failed": failed, "refunds_total": completed + in_progress + failed})
        if paid:
            inv.add(Fact("PAYMENT", f"The amount paid for this order is {_money(paid)} and the total refunded so far is {_money(refunded)}.", None))
    status = s.get("return_status")
    if status == "APPROVED" and s.get("refunds_total", 0) == 0:
        s["approved_no_refund"] = True
        inv.add(Fact("REFUND", "The return is approved, but no refund has been recorded for this order yet.", None))
        inv.inferences.append(Inference("The refund is a separate step after approval and may not have been started yet.", "Approval records that the goods are accepted back; money is handled separately"))
        inv.missing.append(Missing("A refund request or transaction reference", "None exists in your account records yet"))
    elif s.get("refunds_in_progress", 0) > 0:
        s["refund_in_progress"] = True
    elif s.get("refunds_failed", 0) > 0:
        s["refund_failed"] = True
    if s.get("refunds_completed", 0) > 0:
        s["refund_completed"] = True
        inv.missing.append(Missing("Confirmation that the money has reached your bank or card", "DSports can see that the refund is completed on its side, not when your bank credits it"))
    if inv.effective == Complaint.REFUND_PARTIAL and paid and 0 < refunded < paid - 0.005:
        s["partial_confirmed"] = True
        inv.add(Fact("REFUND", f"The refunded amount ({_money(refunded)}) is less than the amount paid ({_money(paid)}), a difference of {_money(paid - refunded)}.", None))
        inv.inferences.append(Inference("The difference may be because only some items were returned, or because some charges are not refundable under the refund policy.",
                                        "The refund total is below the amount paid"))
    if not rows and s.get("refunds_total", 0) == 0:
        s["nothing_to_refund"] = True
        inv.add(Fact("REFUND", "There is no return and no refund recorded for this order.", None))


def _returns(inv: Investigation, returns: dict | None, eligibility: dict | None) -> None:
    s = inv.signals
    _return_facts(inv, returns)
    if eligibility is not None:
        s["eligible"] = bool(eligibility.get("eligible"))
        s["eligibility_reason"] = eligibility.get("reason")
        inv.add(Fact("RETURN", f"DSports' return rules say: {eligibility.get('message') or eligibility.get('reason')}", None))
        if eligibility.get("return_deadline"):
            inv.add(Fact("RETURN", f"The return deadline for this product is {templates.when(eligibility['return_deadline'])}.", None))
        inv.signals["max_returnable"] = eligibility.get("max_returnable_quantity")
    if inv.effective == Complaint.RETURN_PICKUP:
        inv.missing.append(Missing("Pickup details (date, courier, attempt)", "The return record does not contain any pickup or courier information"))


# ---------------------------------------------------------------------------------------------------- product-line complaints

def _product_line(inv: Investigation, tracking: dict | None, eligibility: dict | None, product: dict | None, reviews: dict | None, req: Request) -> None:
    s = inv.signals
    order = inv.order or {}
    if tracking is not None:
        sh = tracking.get("shipment")
        if sh is None:
            s["no_shipment"] = True
            inv.add(Fact("SHIPMENT", "No shipment has been created for this order, so nothing has been delivered yet.", order.get("order_number")))
        else:
            s["delivered"] = sh.get("status") == "DELIVERED"
            ref = sh.get("tracking_number")
            if s["delivered"]:
                inv.add(Fact("SHIPMENT", f"The shipment is marked delivered" + (f" on {templates.when(sh['delivered_at'])}" if sh.get("delivered_at") else "") + ".", ref))
            else:
                inv.add(Fact("SHIPMENT", f"The shipment status is {sh.get('status')}, so the order is not marked delivered yet.", ref))
    if eligibility is not None:
        s["eligible"] = bool(eligibility.get("eligible"))
        s["eligibility_reason"] = eligibility.get("reason")
        inv.add(Fact("RETURN", f"DSports' return rules for this product say: {eligibility.get('message') or eligibility.get('reason')}", None))
        if eligibility.get("eligible") and eligibility.get("return_deadline"):
            inv.add(Fact("RETURN", f"It can be returned until {templates.when(eligibility['return_deadline'])}.", None))
    if product is not None:
        card = product.get("product", {})
        bits = [f"{k}: {v}" for k, v in (("brand", card.get("brand")), ("category", card.get("category"))) if v]
        inv.add(Fact("PRODUCT", f"The listing for {card.get('name')} shows " + ("; ".join(bits) if bits else "no further specification") + ".", card.get("id")))
        s["listing_price"] = (card.get("price") or {}).get("selling_price") if isinstance(card.get("price"), dict) else card.get("price")
    if reviews is not None and reviews.get("total_reviews"):
        inv.add(Fact("PRODUCT", f"The product has {reviews['total_reviews']} approved review(s) with an average rating of {reviews.get('average_rating')}. Reviews are context, not proof.", None))
    if inv.effective in (Complaint.DAMAGED_PRODUCT, Complaint.WRONG_PRODUCT, Complaint.PRODUCT_DEFECT, Complaint.PRODUCT_MISMATCH):
        inv.missing.append(Missing("Photos of the item and packaging", "This assistant cannot receive or assess images, so a person would need to ask for them"))
    if inv.effective == Complaint.DAMAGED_PRODUCT and len(req.description.split()) < 6:
        inv.missing.append(Missing("What exactly is damaged", "A short description helps the team check the right thing"))
