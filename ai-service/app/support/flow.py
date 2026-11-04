"""The Support Resolution workflow nodes (called by the LangGraph `support` subgraph).

    understand -> identify order/product (from the customer's OWN records, or ask) -> investigate (read-only, parallel, bounded)
    -> decide (deterministic) -> respond: facts / possibilities / unverified / recommendation
    -> if a person must look: PROPOSE the case, which the customer confirms (single-use token, idempotent, audited) -> backend creates it.

Nothing here refunds, cancels, approves or reverses anything, and a case ID is only ever shown after the backend returned one. Identity
is the forwarded token; every order, product and case id comes from tool results or from offers this service made itself.
"""
from __future__ import annotations

import dataclasses
import logging
import re
from datetime import datetime, timedelta, timezone
from typing import Any

from app.agent import orders as order_flow
from app.agent import templates
from app.agent.offers import add_action, add_chip
from app.agent.runtime import Runtime
from app.router.intents import Intent, Route
from app.support import respond
from app.support.classify import classify, safety_flag, wants_human
from app.support.investigate import Request, investigate
from app.support.model import DELIVERY, NEEDS_PRODUCT, PAYMENT, Complaint, Outcome
from app.support.resolve import case_subject, decide
from app.tools.dsports_client import ToolError

log = logging.getLogger(__name__)

_DISPUTE = re.compile(r"\b(that'?s|this is|it'?s) (wrong|not (right|true|correct|acceptable|fair|good enough))\b|\bi (disagree|dispute|don'?t accept|do not accept)\b|\bunacceptable\b|"
                      r"\byou(?:'re| are) wrong\b|\b(this|that) (doesn'?t|does not) help\b|\bnot satisfied\b", re.IGNORECASE)
STATUS_FOR = {  # which order statuses a complaint most plausibly concerns, used only to narrow a list the customer then confirms
    Complaint.MISSING_DELIVERY: {"DELIVERED"}, Complaint.DELAYED_DELIVERY: {"CONFIRMED", "PROCESSING", "SHIPPED"}, Complaint.PARTIAL_DELIVERY: {"SHIPPED", "DELIVERED"},
}
DELIVERED_LIKE = {"DELIVERED", "REFUNDED"}


def _describe(message: str) -> str:
    return " ".join(message.split())[:1500]


def _state(rt: Runtime) -> dict[str, Any]:
    return dict(rt.conv.slots.get("support") or {})


def _save(rt: Runtime, **fields: Any) -> None:
    st = _state(rt)
    st.update({k: v for k, v in fields.items()})
    rt.conv.slots["support"] = {k: v for k, v in st.items() if v is not None}


def _fail(rt: Runtime, result, subject: str) -> None:
    rt.turn.text = rt.turn.fallback_text = f"I couldn't get {subject}: {result.error_message}"
    rt.turn.status = "error" if result.error_code not in ("NOT_FOUND", "FORBIDDEN") else "ok"
    rt.turn.error_code = result.error_code


# ---------------------------------------------------------------------------------------------------- the complaint flow

def _complaint_type(route: Route, message: str, st: dict[str, Any]) -> Complaint:
    explicit = route.slots.get("complaint")
    if explicit:
        try:
            return Complaint(explicit)
        except ValueError:
            pass
    found = classify(message)
    if found and not (route.slots.get("followup") and st.get("type") and found == Complaint.GENERAL):
        return found
    if st.get("type") and (route.slots.get("followup") or route.slots.get("dispute")):
        return Complaint(st["type"])
    return Complaint.GENERAL


async def complaint(rt: Runtime, route: Route, message: str) -> None:
    if rt.case:                                   # a case-linked conversation: look at THIS case's order, and re-check from the case's own description
        if route.slots.get("from_case"):
            message = rt.case.get("description") or rt.case.get("subject") or message
        if not (route.slots.get("order_id") or route.slots.get("order_number")) and rt.case.get("order_number"):
            route.slots = {**route.slots, "order_number": rt.case["order_number"]}
    st = _state(rt)
    ctype = _complaint_type(route, message, st)
    new_statement = (not route.slots.get("followup")) or classify(message) is not None or not st.get("description")
    description = _describe(message) if new_statement else st["description"]
    safety = safety_flag(message) or bool(st.get("safety"))
    human = wants_human(message) or bool(route.slots.get("human_requested"))
    disputes = bool(route.slots.get("dispute")) or (bool(_DISPUTE.search(message)) and st.get("stage") in ("investigated", "case_proposed"))

    # Came from "a person should look at my <delivery/order/...> complaint": the complaint WAS stated in the same message. A bare "I want a human"
    # (no subject) still goes through the honest "what is it about / I cannot connect you live" path.
    already_described = bool(route.slots.get("human_requested")) and bool(re.search(
        r"\b(order|package|parcel|shipment|delivery|refund|payment|return|product|item|complaint about)\b", message, re.IGNORECASE))
    if ctype == Complaint.GENERAL and (len(description.split()) < 7 or (human and not route.slots.get("order_number") and not already_described)) \
            and not route.slots.get("order_id") and not route.slots.get("followup"):
        return await _ask_what(rt, ctype, description, human)

    order_data, ok = await _identify_order(rt, ctype, route, st, description)
    if not ok:
        return
    product, ok = _identify_product(rt, ctype, route, st, order_data, description)
    if not ok:
        return
    req = Request(complaint=ctype, description=description, order_id=order_data["order_id"] if order_data else None, order_data=order_data,
                  product_id=product["product_id"] if product else None, product_name=product["name"] if product else None, safety_related=safety,
                  question=message if ctype == Complaint.PRODUCT_MANUAL else None)
    await respond_to(rt, req, st, disputes=disputes, wants_human=human, force_case=bool(route.slots.get("force_case")))


async def respond_to(rt: Runtime, req: Request, st: dict[str, Any], *, disputes: bool, wants_human: bool, force_case: bool) -> None:
    inv = await investigate(rt, req)
    decision = decide(inv, disputes=disputes, wants_human=wants_human)
    if force_case and decision.outcome in (Outcome.ANSWER, Outcome.SAFE_NEXT_STEP):
        decision = dataclasses.replace(decision, outcome=Outcome.CREATE_CASE, case_subject=case_subject(inv), reasons=[*decision.reasons, "The customer asked for a case"])
    text = respond.findings_text(inv, decision)
    signal = await _platform_signal(rt, inv.complaint)
    if signal:
        text = f"{text}\n\n{signal}"
    rt.turn.support = respond.investigation_payload(inv, decision)
    rt.turn.fallback_text = rt.turn.text = text
    if inv.order:
        rt.conv.last_order = {"id": inv.order["order_id"], "orderNumber": inv.order["order_number"]}
    if inv.policy:
        rt.turn.sources = [{"source": p["source"], "title": p["title"], "page": 1, "document_type": p.get("document_type"), "chunk_id": p["chunk_id"],
                            "snippet": p["snippet"][:200]} for p in inv.policy[:2]]
    _save(rt, type=inv.complaint.value, stage="investigated", order_id=inv.order["order_id"] if inv.order else None,
          order_number=inv.order["order_number"] if inv.order else None, product_id=(inv.product or {}).get("product_id"),
          product_name=(inv.product or {}).get("name"), description=req.description, safety=inv.safety_related or None)

    if decision.outcome == Outcome.UNVERIFIABLE:
        rt.turn.error_code = "UPSTREAM_UNAVAILABLE"
        rt.turn.text = rt.turn.fallback_text = decision.recommendation
        return
    if rt.case and decision.outcome in (Outcome.CREATE_CASE, Outcome.ESCALATE):
        return await _linked_case_next_step(rt, text, decision)          # never a second case for the same problem
    if decision.outcome in (Outcome.CREATE_CASE, Outcome.ESCALATE):
        args = respond.case_arguments(inv, decision, req.description, escalation_reason=decision.escalation_reason)
        if await order_flow._propose(rt, "create_support_case", args):
            summary = rt.turn.actions[0]["summary"]
            rt.turn.text = rt.turn.fallback_text = f"{text}\n\nPlease confirm: {summary}.\n{order_flow.CONFIRM_HINT}"
            _save(rt, stage="case_proposed")
        else:
            rt.turn.text = rt.turn.fallback_text = f"{text}\n\n{rt.turn.text}"
        for label, intent, slots in decision.actions:
            add_action(rt, label, Intent(intent), slots)
        return
    if decision.outcome == Outcome.ASK_INFO and decision.ask:
        rt.turn.text = rt.turn.fallback_text = f"{text}\n\n{decision.ask}"
        _save(rt, stage="awaiting_order")
        return
    for label, intent, slots in decision.actions:
        add_action(rt, label, Intent(intent), slots)
    if rt.case:
        _offer_findings_for_case(rt, text)
    else:
        add_action(rt, "Raise a support case about this", Intent.CASE_CREATE, {})
    if decision.outcome == Outcome.ANSWER:
        follow_up = (f"I can ask for a person to review case {rt.case['case_number']}." if rt.case else "I can raise a support case so a person follows up.")
        rt.turn.text = rt.turn.fallback_text = f"{text}\n\nIf this doesn't solve it, {follow_up}"


# ---------------------------------------------------------------------------------------------------- the conversation is about one of the customer's cases

_CATEGORY_TO_COMPLAINT = {"PAYMENT_ISSUE": Complaint.PAYMENT_FAILED, "REFUND_ISSUE": Complaint.REFUND_DELAYED, "RETURN_ISSUE": Complaint.RETURN_BLOCKED,
                          "PRODUCT_COMPLAINT": Complaint.PRODUCT_DEFECT}
_TECHNICAL = {Complaint.PAYMENT_FAILED, Complaint.PAYMENT_PENDING, Complaint.DUPLICATE_PAYMENT, Complaint.TECHNICAL_ISSUE}


def link_case(rt: Runtime, case: dict[str, Any]) -> None:
    """Make case (just re-read from DSports with the customer's token) the case of this conversation."""
    _save(rt, case_number=case["case_number"], case_id=case["case_id"], stage="case_open" if case["status"] != "RESOLVED" else "case_resolved")


def _case_complaint(case: dict[str, Any]) -> Complaint:
    category = case.get("category") or "GENERAL"
    if category in _CATEGORY_TO_COMPLAINT:
        return _CATEGORY_TO_COMPLAINT[category]
    try:
        return Complaint(category)
    except ValueError:
        return Complaint.GENERAL


def case_suggestions(rt: Runtime) -> None:
    """Next steps offered in a case-linked conversation, merged (without duplicates) with the ones the answer already carries. Every one is an
    ordinary assistant step: the writes still need the customer's confirmation, so nothing is added while a confirmation is pending."""
    case = rt.case
    if case is None or rt.turn.actions:
        return
    number = case["case_number"]
    have = {s["label"] for s in rt.turn.suggestions}

    def action(label: str, intent: Intent, slots: dict[str, Any]) -> None:
        if label not in have:
            add_action(rt, label, intent, slots)
            have.add(label)

    def chip(label: str) -> None:
        if label not in have:
            add_chip(rt, label)
            have.add(label)

    if case["status"] != "RESOLVED":
        action("Check this again", Intent.COMPLAINT, {"from_case": True, "followup": False, "complaint": _case_complaint(case).value})
        chip("What is the latest on this case?")
        chip("Add a message to my case")
        if (case.get("escalation") or {}).get("state", "NONE") == "NONE":
            action("Ask for a person to review it", Intent.CASE_ESCALATE, {"case_ref": number})
    elif case.get("can_reopen"):
        action("Reopen this case", Intent.CASE_REOPEN, {"case_ref": number})

def _offer_findings_for_case(rt: Runtime, text: str) -> None:
    case = rt.case
    if case and case["status"] != "RESOLVED":
        body = ("Details the DSports assistant checked for me: " + text.replace("\n", " "))[:1900]
        add_action(rt, "Add these findings to my case", Intent.CASE_MESSAGE, {"case_ref": case["case_number"], "text": body})


async def _linked_case_next_step(rt: Runtime, text: str, decision) -> None:
    """A person has to look at it, and the conversation already has a case: ask for a review of THAT case (confirmed) instead of opening another."""
    case = rt.case
    number = case["case_number"]
    if case["status"] == "RESOLVED":
        rt.turn.text = rt.turn.fallback_text = f"{text}\n\nCase {number} is marked resolved. If this is not solved you can reopen it."
        if case.get("can_reopen"):
            add_action(rt, "Reopen this case", Intent.CASE_REOPEN, {"case_ref": number})
        return
    state = (case.get("escalation") or {}).get("state", "NONE")
    if state == "NONE":
        reason = (decision.escalation_reason or "The assistant's checks could not resolve this; a person should review it")[:500]
        if await order_flow._propose(rt, "request_case_escalation", {"case_ref": number, "reason": reason}):
            rt.turn.text = rt.turn.fallback_text = f"{text}\n\nThis needs a person. Please confirm: {rt.turn.actions[0]['summary']}.\n{order_flow.CONFIRM_HINT}"
        else:
            rt.turn.text = rt.turn.fallback_text = f"{text}\n\n{rt.turn.text}"
        return
    rt.turn.text = rt.turn.fallback_text = (f"{text}\n\nCase {number} is already with the support team (a review was requested: state {state.lower()}). "
                                            f"I have not opened a second case.")
    _offer_findings_for_case(rt, text)


async def _platform_signal(rt: Runtime, ctype: Complaint) -> str | None:
    """Engineering AI, bounded and customer-safe: for payment and technical complaints, whether REAL (never synthetic) logs show recent database, timeout or
    downstream errors. The answer is one hedged sentence: no log text, counts, ids, stack traces or other customers' data, and never a claim that anything is fixed."""
    repo = getattr(rt.services, "engineering", None)
    if ctype not in _TECHNICAL or repo is None:
        return None
    try:
        from app.engineering.repository import LogFilter
        now = datetime.now(timezone.utc)
        clusters = await repo.clusters(LogFilter(now - timedelta(hours=24), now, ("backend",), "ERROR", "real"))
    except Exception:  # noqa: BLE001 - optional context; its absence changes nothing for the customer
        log.info("engineering signal unavailable", exc_info=True)
        return None
    if not any(c.get("category") in ("DATABASE", "TIMEOUT", "DOWNSTREAM") for c in clusters):
        return None
    return ("Our monitoring recorded recent technical errors on the shop's servers. That may be related to what you saw, but I have not confirmed that it is the cause, "
            "and nothing has been fixed because of this message. A person can look into it if you ask.")


async def _ask_what(rt: Runtime, ctype: Complaint, description: str, human: bool) -> None:
    rt.turn.support = respond.payload("ASK_INFO", complaint=ctype.value, ask="What is the problem?")
    lead = ("I can't connect you to a person live, but I can open a support case and ask for a person to review it. " if human
            else "I'm sorry you're having a problem. ")
    rt.turn.text = rt.turn.fallback_text = lead + "What is it about? Tell me in a sentence (and the order number if you have it), or pick one:"
    for label, phrase in (("My order hasn't arrived", "My order has not arrived"), ("A payment problem", "My payment was deducted but my order failed"),
                          ("My refund is late", "My return was approved but I haven't received my refund"), ("A damaged or wrong item", "The product I received is damaged")):
        add_chip(rt, label, phrase)
    _save(rt, type=ctype.value, stage="awaiting_details", description=description)


# ---------------------------------------------------------------------------------------------------- identifying the order and the product

async def _identify_order(rt: Runtime, ctype: Complaint, route: Route, st: dict[str, Any], description: str) -> tuple[dict | None, bool]:
    s = route.slots
    ref: dict[str, Any] | None = None
    if s.get("order_id"):
        ref = {"order_id": s["order_id"]}
    elif s.get("order_number"):
        ref = {"order_number": str(s["order_number"])}
    elif s.get("latest"):
        ref = {"latest": True}
    elif ctype == Complaint.TECHNICAL_ISSUE:
        return None, True                     # a website/app problem is not about an order unless the customer names one
    elif st.get("order_id") and not s.get("product_hint") and not s.get("period_label") and (st.get("type") == ctype.value or s.get("followup")):
        ref = {"order_id": st["order_id"]}           # the order already being discussed, unless the customer just pointed at a different one
    elif ctype in PAYMENT:
        return None, True                     # payments can be investigated without naming an order
    elif rt.conv.last_order and rt.conv.last_order.get("id") and not s.get("product_hint") and not s.get("period_label"):
        ref = {"order_id": rt.conv.last_order["id"]}
    if ref is None:
        listing = await rt.call("get_orders", {"limit": 8, **{k: s[k] for k in ("since", "until") if s.get(k)}})
        if not listing.ok:
            _fail(rt, listing, "your orders")
            return None, False
        candidates = _narrow(ctype, listing.data["orders"], s.get("product_hint"))
        if len(candidates) == 1:
            ref = {"order_id": candidates[0]["order_id"]}
        elif not candidates:
            rt.turn.text = rt.turn.fallback_text = ("I couldn't find a matching order on your account. Tell me the order number "
                                                    "(it starts with ORD-) and I'll check it.")
            rt.turn.support = respond.payload("ASK_INFO", complaint=ctype.value, ask="Which order?")
            _save(rt, type=ctype.value, stage="awaiting_order", description=description)
            return None, False
        else:
            _ask_which_order(rt, ctype, candidates, description)
            return None, False
    result = await rt.call("get_order", ref)
    if not result.ok:
        if result.error_code in ("NOT_FOUND", "FORBIDDEN"):
            rt.turn.text = rt.turn.fallback_text = "I couldn't find that order on your account. Tell me the order number (it starts with ORD-) and I'll check it."
            rt.turn.support = respond.payload("ASK_INFO", complaint=ctype.value, ask="Which order?")
            _save(rt, type=ctype.value, stage="awaiting_order", description=description)
        else:
            _fail(rt, result, "that order")
        return None, False
    return result.data, True


def _narrow(ctype: Complaint, orders: list[dict[str, Any]], hint: str | None) -> list[dict[str, Any]]:
    rows = list(orders)
    if hint:
        words = order_flow._hint_words(hint)
        hinted = [o for o in rows if any(re.search(rf"\b{re.escape(w)}\b", i["name"].lower()) for i in o.get("items", []) for w in words)]
        if hinted:                                         # a hint that matches nothing must not hide the customer's orders
            return hinted[:1] if len(hinted) == 1 else hinted[:4]
    wanted = STATUS_FOR.get(ctype)
    if ctype in NEEDS_PRODUCT or ctype.value.startswith(("REFUND", "RETURN")):
        wanted = DELIVERED_LIKE
    if wanted:
        narrowed = [o for o in rows if o.get("status") in wanted]
        if narrowed:
            rows = narrowed
    rows = [o for o in rows if o.get("status") != "CANCELLED"] or rows
    return rows[:1] if len(rows) == 1 else rows[:4]


def _ask_which_order(rt: Runtime, ctype: Complaint, candidates: list[dict[str, Any]], description: str) -> None:
    rt.turn.orders = candidates
    rt.turn.support = respond.payload("ASK_INFO", complaint=ctype.value, ask="Which order is this about?")
    rt.turn.text = rt.turn.fallback_text = "I found several recent orders. Which one is this about?\n" + "\n".join(f"- {templates.order_line(o)}" for o in candidates)
    for o in candidates:
        first = (o.get("items") or [{}])[0].get("name")
        add_action(rt, f"{o['order_number']}" + (f" - {first}" if first else ""), Intent.COMPLAINT,
                   {"complaint": ctype.value, "order_id": o["order_id"], "followup": True})
    _save(rt, type=ctype.value, stage="awaiting_order", description=description)


def _identify_product(rt: Runtime, ctype: Complaint, route: Route, st: dict[str, Any], order: dict | None, description: str) -> tuple[dict | None, bool]:
    if order is None or (ctype not in NEEDS_PRODUCT and ctype != Complaint.PARTIAL_DELIVERY):
        return None, True
    items = order.get("items", [])
    by_id = {i["product_id"]: i for i in items}
    wanted = route.slots.get("product_id") or (st.get("product_id") if st.get("order_id") == order["order_id"] else None)
    if wanted in by_id:
        return {"product_id": wanted, "name": by_id[wanted]["name"]}, True
    hint = route.slots.get("product_hint")
    if hint:
        words = order_flow._hint_words(hint)
        hits = [i for i in items if any(re.search(rf"\b{re.escape(w)}\b", i["name"].lower()) for w in words)]
        if len(hits) == 1:
            return {"product_id": hits[0]["product_id"], "name": hits[0]["name"]}, True
    if len(items) == 1:
        return {"product_id": items[0]["product_id"], "name": items[0]["name"]}, True
    if ctype == Complaint.PARTIAL_DELIVERY and not items:
        return None, True
    rt.turn.support = respond.payload("ASK_INFO", complaint=ctype.value, ask="Which item?")
    rt.turn.text = rt.turn.fallback_text = f"Order {order['order_number']} has several items. Which one is this about?"
    for i in items[:6]:
        add_action(rt, i["name"], Intent.COMPLAINT, {"complaint": ctype.value, "order_id": order["order_id"], "product_id": i["product_id"], "followup": True})
    _save(rt, type=ctype.value, stage="awaiting_product", description=description, order_id=order["order_id"], order_number=order["order_number"])
    return None, False


# ---------------------------------------------------------------------------------------------------- raising a case from what was found

async def case_create(rt: Runtime, route: Route, message: str) -> None:
    st = _state(rt)
    if not st.get("type") or st.get("stage") not in ("investigated", "case_proposed", "awaiting_details"):
        route.slots = {**route.slots, "followup": False}
        return await complaint(rt, Route(Intent.COMPLAINT, route.tier, {"complaint": Complaint.GENERAL.value}), message)
    slots = {"complaint": st["type"], "followup": True, "force_case": True}
    if st.get("order_id"):
        slots["order_id"] = st["order_id"]
    if st.get("product_id"):
        slots["product_id"] = st["product_id"]
    await complaint(rt, Route(Intent.COMPLAINT, route.tier, slots), st.get("description") or message)


# ---------------------------------------------------------------------------------------------------- existing cases

async def _target_case(rt: Runtime, route: Route, *, unresolved: bool) -> dict[str, Any] | None:
    """The case the customer means: named, or the one in this conversation, or their only unresolved case; otherwise ask (never guess)."""
    ref = route.slots.get("case_ref") or _state(rt).get("case_number")
    if ref:
        result = await rt.call("get_support_case", {"case_ref": ref})
        if result.ok:
            return result.data
        _fail(rt, result, "that case")
        return None
    listing = await rt.call("get_customer_support_cases", {"unresolved_only": unresolved, "limit": 5})
    if not listing.ok:
        _fail(rt, listing, "your cases")
        return None
    cases = listing.data["cases"]
    if not cases:
        rt.turn.text = rt.turn.fallback_text = "You have no open support cases." if unresolved else "You have no support cases yet."
        return None
    if len(cases) > 1:
        rt.turn.text = rt.turn.fallback_text = "You have more than one case. Which one do you mean?\n" + "\n".join(f"- {c['case_number']}: {c['subject']} - {c['status']}" for c in cases)
        rt.turn.support = respond.payload("CASE_LIST", cases=cases)
        for c in cases[:4]:
            add_action(rt, c["case_number"], route.intent, {**route.slots, "case_ref": c["case_number"]})
        return None
    full = await rt.call("get_support_case", {"case_ref": cases[0]["case_number"]})
    if not full.ok:
        _fail(rt, full, "that case")
        return None
    return full.data


async def case_status(rt: Runtime, route: Route, message: str) -> None:
    if route.slots.get("list"):
        unresolved = bool(route.slots.get("unresolved_only"))
        result = await rt.call("get_customer_support_cases", {"unresolved_only": unresolved, "limit": 10})
        if not result.ok:
            return _fail(rt, result, "your cases")
        rt.turn.support = respond.payload("CASE_LIST", cases=result.data["cases"], count=result.data["count"])
        rt.turn.text = rt.turn.fallback_text = respond.cases_list_text(result.data, unresolved)
        for c in result.data["cases"][:3]:
            add_action(rt, f"Status of {c['case_number']}", Intent.CASE_STATUS, {"case_ref": c["case_number"]})
        return
    case = await _target_case(rt, route, unresolved=False)
    if case is None:
        return
    _save(rt, case_number=case["case_number"], case_id=case["case_id"], stage="case_open" if case["status"] != "RESOLVED" else "case_resolved")
    rt.turn.support = respond.payload("CASE", case=respond.case_card(case))
    rt.turn.text = rt.turn.fallback_text = respond.case_status_text(case)
    if case["status"] != "RESOLVED":
        if case["escalation"]["state"] == "NONE":
            add_action(rt, "Ask for a person to review it", Intent.CASE_ESCALATE, {"case_ref": case["case_number"]})
        add_chip(rt, "Add a message to this case", "Add a message to my case")
    elif case.get("can_reopen"):
        add_action(rt, "Reopen this case", Intent.CASE_REOPEN, {"case_ref": case["case_number"]})


_TEXT_AFTER = re.compile(r"(?::|\bsaying\b|\bthat\b|\bwith\b|\bto say\b)\s*(.{3,1900})$", re.IGNORECASE | re.DOTALL)


def _body(route: Route, message: str) -> str:
    if route.slots.get("text"):
        return str(route.slots["text"])
    m = _TEXT_AFTER.search(message)
    return (m.group(1) if m else message).strip()


async def case_message(rt: Runtime, route: Route, message: str) -> None:
    case = await _target_case(rt, route, unresolved=True)
    if case is None:
        return
    if case["status"] == "RESOLVED":
        rt.turn.text = rt.turn.fallback_text = f"Case {case['case_number']} is resolved, so I can't add a message to it. You can reopen it if it isn't solved."
        if case.get("can_reopen"):
            add_action(rt, "Reopen this case", Intent.CASE_REOPEN, {"case_ref": case["case_number"]})
        return
    body = _body(route, message)
    if len(body.split()) < 2 or re.fullmatch(r"(add|send)( a)? (message|note|update)( to (my|the) (case|complaint))?\.?", body.strip(), re.IGNORECASE):
        rt.turn.text = rt.turn.fallback_text = f"What would you like to tell support about case {case['case_number']}? Type your message and I'll ask you to confirm before I add it."
        _save(rt, case_number=case["case_number"], case_id=case["case_id"], stage="awaiting_case_message")
        return
    await order_flow._propose(rt, "add_case_message", {"case_ref": case["case_number"], "message": body})
    _save(rt, case_number=case["case_number"], case_id=case["case_id"])


async def case_escalate(rt: Runtime, route: Route, message: str) -> None:
    if not (route.slots.get("case_ref") or _state(rt).get("case_number")) and not await _has_open_case(rt):
        # No case yet: open one instead, but KEEP the customer's request for a person, so the case is created with an escalation request (REQUESTED).
        return await complaint(rt, Route(Intent.COMPLAINT, route.tier, {"complaint": Complaint.GENERAL.value, "human_requested": True}), message)
    case = await _target_case(rt, route, unresolved=True)
    if case is None:
        return
    reason = _body(route, message) if len(message.split()) > 8 else "The customer asked for a person to review this case"
    await order_flow._propose(rt, "request_case_escalation", {"case_ref": case["case_number"], "reason": reason[:500]})
    _save(rt, case_number=case["case_number"], case_id=case["case_id"])


async def _has_open_case(rt: Runtime) -> bool:
    result = await rt.call("get_customer_support_cases", {"unresolved_only": True, "limit": 1})
    return bool(result.ok and result.data["count"])


async def case_reopen(rt: Runtime, route: Route, message: str) -> None:
    case = await _target_case(rt, route, unresolved=False)
    if case is None:
        return
    reason = _body(route, message) if len(message.split()) > 6 else "The problem is not solved"
    await order_flow._propose(rt, "reopen_support_case", {"case_ref": case["case_number"], "reason": reason[:500]})


# ---------------------------------------------------------------------------------------------------- confirmed actions

def apply_support_outcome(rt: Runtime, outcome) -> bool:
    """Text and payload for a confirmed support WRITE, built only from what the backend returned. True when handled."""
    if outcome.tool not in ("create_support_case", "add_case_message", "request_case_escalation", "reopen_support_case"):
        return False
    r = outcome.result or {}
    case = {k: v for k, v in r.items() if k not in ("outcome", "action")}
    rt.turn.support = respond.payload("CASE_CREATED" if outcome.tool == "create_support_case" else "CASE", case=respond.case_card(case), outcome=r.get("outcome"))
    if outcome.tool == "create_support_case":
        rt.turn.text = respond.created_text(r)
    elif outcome.tool == "add_case_message":
        rt.turn.text = f"Done. I added your message to case {r['case_number']}. Status: {r['status']}."
    elif outcome.tool == "request_case_escalation":
        rt.turn.text = f"Done. I recorded your request for a person to review case {r['case_number']}. " + (respond.escalation_sentence(r.get("escalation")) or "")
    else:
        rt.turn.text = f"Done. Case {r['case_number']} is open again (status {r['status']})."
    rt.turn.fallback_text = rt.turn.text
    _save(rt, case_number=r["case_number"], case_id=r["case_id"], stage="case_open")
    return True
