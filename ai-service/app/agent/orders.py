"""Orders / returns / reorder subgraph nodes.

Lookups are deterministic tool calls rendered by templates (no model tokens). State-changing requests build a PROPOSAL from
live data and stop at a confirmation: nothing is created, added or paid until the customer confirms.
"""
from __future__ import annotations

import logging
import re
from typing import Any

from app.agent.offers import add_offer
from app.agent import shopping, templates
from app.agent.runtime import Runtime
from app.router.extract import CATEGORIES
from app.router.intents import Intent, Route
from app.tools.dsports_client import ToolError

log = logging.getLogger(__name__)
DEFAULT_RETURN_REASON = "Return requested by the customer through the shopping assistant"
CONFIRM_HINT = "Reply \"yes\" or press Confirm to go ahead (valid for 5 minutes), or \"no\" to cancel."


def _order_ref(route: Route, rt: Runtime, *, default_latest: bool = True) -> dict[str, Any]:
    s = route.slots
    if s.get("order_number"):
        return {"order_number": str(s["order_number"])}
    if s.get("order_id"):
        return {"order_id": s["order_id"]}
    if s.get("latest"):
        return {"latest": True}
    if rt.conv.last_order and rt.conv.last_order.get("id"):
        return {"order_id": rt.conv.last_order["id"]}
    return {"latest": True} if default_latest else {}


def _fail(rt: Runtime, result, subject: str = "that") -> None:
    rt.turn.text = rt.turn.fallback_text = f"I couldn't get {subject}: {result.error_message}"
    rt.turn.status = "error" if result.error_code not in ("NOT_FOUND", "FORBIDDEN") else "ok"
    rt.turn.error_code = result.error_code


# ------------------------------------------------------------------------------------------------ lookups

async def orders_list(rt: Runtime, route: Route) -> None:
    args = {k: route.slots[k] for k in ("since", "until") if route.slots.get(k)}
    result = await rt.call("get_orders", {**args, "limit": 10})
    if not result.ok:
        return _fail(rt, result, "your orders")
    rt.turn.orders = result.data["orders"][:1] if route.slots.get("latest") else result.data["orders"]   # newest first
    if rt.turn.orders:
        rt.conv.last_order = {"id": rt.turn.orders[0]["order_id"], "orderNumber": rt.turn.orders[0]["order_number"]}
    if route.slots.get("latest") and rt.turn.orders:
        rt.turn.text = rt.turn.fallback_text = "Your latest order:\n- " + templates.order_line(rt.turn.orders[0])
    else:
        rt.turn.text = rt.turn.fallback_text = templates.orders_text(result.data, route.slots.get("period_label"))


async def order_detail(rt: Runtime, route: Route) -> None:
    result = await rt.call("get_order", _order_ref(route, rt))
    if not result.ok:
        return _fail(rt, result, "that order")
    rt.turn.orders = [result.data]
    rt.conv.last_order = {"id": result.data["order_id"], "orderNumber": result.data["order_number"]}
    rt.turn.text = rt.turn.fallback_text = templates.order_text(result.data)


async def track(rt: Runtime, route: Route) -> None:
    result = await rt.call("track_order", _order_ref(route, rt))
    if not result.ok:
        return _fail(rt, result, "the tracking details")
    rt.turn.tracking = result.data
    rt.conv.last_order = {"id": result.data["order_id"], "orderNumber": result.data["order_number"]}
    rt.turn.text = rt.turn.fallback_text = templates.tracking_text(result.data)


async def payment_status(rt: Runtime, route: Route) -> None:
    ref = _order_ref(route, rt, default_latest=False)
    args: dict[str, Any] = {}
    if ref:  # resolve the reference to an order id the payment tool understands
        order = await rt.call("get_order", ref)
        if not order.ok:
            return _fail(rt, order, "that order")
        args["order_id"] = order.data["order_id"]
    result = await rt.call("get_payment_status", args)
    if not result.ok:
        return _fail(rt, result, "your payments")
    rt.turn.payments = result.data["payments"]
    rt.turn.text = rt.turn.fallback_text = templates.payments_text(result.data)


async def refund_status(rt: Runtime, route: Route) -> dict[str, Any] | None:
    ref = _order_ref(route, rt, default_latest=False) if (route.slots.get("order_number") or route.slots.get("order_id")) else {}
    args: dict[str, Any] = {}
    if ref:
        order = await rt.call("get_order", ref)
        if not order.ok:
            _fail(rt, order, "that order")
            return None
        args["order_id"] = order.data["order_id"]
    result = await rt.call("get_refund_status", args)
    if not result.ok:
        _fail(rt, result, "your refunds")
        return None
    rt.turn.refunds = result.data["payments"]
    rt.turn.text = rt.turn.fallback_text = templates.refunds_text(result.data)
    return result.data


async def cart_view(rt: Runtime, route: Route) -> None:
    result = await rt.call("get_cart", {})
    if not result.ok:
        return _fail(rt, result, "your cart")
    rt.turn.cart = result.data
    rt.turn.text = rt.turn.fallback_text = templates.cart_text(result.data)


# ------------------------------------------------------------------------------------------------ finding a past purchase

def _hint_words(hint: str | None) -> set[str]:
    if not hint:
        return set()
    h = hint.lower().strip()
    category = CATEGORIES.get(h)
    words = {w for w, c in CATEGORIES.items() if c == category} if category else set()
    return words | {h, h.rstrip("s")}


async def find_purchase(rt: Runtime, route: Route) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    """(chosen line, ambiguous options). A line = {order_id, order_number, status, product_id, name, quantity, unit_price}."""
    result = await rt.call("get_orders", {"limit": 10})
    if not result.ok:
        _fail(rt, result, "your orders")
        return None, []
    wanted_number = str(route.slots.get("order_number") or "").upper()
    wanted_order_id, wanted_product_id = route.slots.get("order_id"), route.slots.get("product_id")    # from an offer the service made
    words = _hint_words(route.slots.get("product_hint"))
    lines: list[dict[str, Any]] = []
    orders = result.data["orders"]                        # newest first
    if route.slots.get("latest") and not wanted_number and not wanted_order_id:
        orders = orders[:1]                               # "my latest order" means that one order, not the newest order that happens to match
    for o in orders:
        if wanted_number and not o["order_number"].upper().endswith(wanted_number.split("-")[-1].zfill(6)):
            continue
        if wanted_order_id and o["order_id"] != wanted_order_id:
            continue
        for i in o.get("items", []):
            if wanted_product_id and i["product_id"] != wanted_product_id:
                continue
            name = i["name"].lower()
            if words and not any(re.search(rf"\b{re.escape(w)}\b", name) for w in words):
                continue
            lines.append({"order_id": o["order_id"], "order_number": o["order_number"], "status": o["status"], "product_id": i["product_id"],
                          "name": i["name"], "quantity": i["quantity"], "unit_price": i["unit_price"]})
    if not lines and not words and rt.conv.slots.get("return_candidate"):
        c = rt.conv.slots["return_candidate"]
        return {"order_id": c["order_id"], "order_number": c.get("order_number"), "product_id": c["product_id"], "name": c["product_name"],
                "quantity": c.get("max_qty", 1), "unit_price": None, "status": "DELIVERED"}, []
    products = {l["product_id"] for l in lines}
    if len(products) > 1:
        return None, lines
    if not lines:
        return None, []
    delivered = [l for l in lines if l["status"] == "DELIVERED"]
    return (delivered or lines)[0], []


def _ask_which(rt: Runtime, options: list[dict[str, Any]], what: str) -> None:
    seen, names, choices = set(), [], []
    purpose, intent = ("RETURN", Intent.RETURN_CHECK) if what == "return" else ("REORDER", Intent.REORDER)
    for o in options:
        if o["product_id"] not in seen:
            seen.add(o["product_id"])
            names.append(f"{o['name']} (order {o['order_number']})")
            # Each option is a server-owned choice: tapping it re-enters the flow with these exact ids (see agent/offers.py).
            choices.append({"choice_id": add_offer(rt, intent, {"order_id": o["order_id"], "product_id": o["product_id"]}, o["name"]),
                            "product_id": o["product_id"], "order_number": o["order_number"], "name": o["name"], "quantity": o["quantity"],
                            "unit_price": o["unit_price"], "status": o["status"]})
    rt.turn.selection = {"purpose": purpose, "prompt": f"Which product would you like to {'return' if purpose == 'RETURN' else 'buy again'}?",
                         "options": choices[:6]}
    rt.turn.text = rt.turn.fallback_text = f"Which one do you mean? I found several products you could {what}:\n" + "\n".join(f"- {n}" for n in names[:6])


# ------------------------------------------------------------------------------------------------ returns

async def return_check(rt: Runtime, route: Route, message: str) -> None:
    line, options = await find_purchase(rt, route)
    if options:
        return _ask_which(rt, options, "return")
    if line is None:
        if rt.turn.status != "error":
            rt.turn.text = rt.turn.fallback_text = ("I couldn't find that product in your orders. Tell me the product name or the order number "
                                                    "and I'll check whether it can be returned.")
        return
    result = await rt.call("check_return_eligibility", {"order_id": line["order_id"], "product_id": line["product_id"]})
    if not result.ok:
        return _fail(rt, result, "the return details")
    e = result.data
    rt.turn.eligibility = {**e, "order_number": line["order_number"]}
    rt.conv.last_order = {"id": line["order_id"], "orderNumber": line["order_number"]}
    if e["eligible"]:
        rt.conv.slots["return_candidate"] = {"order_id": line["order_id"], "order_number": line["order_number"], "product_id": line["product_id"],
                                             "product_name": e["product_name"], "max_qty": e["max_returnable_quantity"]}
    else:
        rt.conv.slots.pop("return_candidate", None)
    next_step = " If you'd like to return it, say \"return them\" and I'll ask you to confirm." if e["eligible"] else ""
    authoritative = f"Order {line['order_number']}: " + templates.eligibility_text(e) + next_step
    rt.turn.fallback_text = authoritative
    # Explain with the policy as a cited source, but the verdict is always DSports'.
    live = {**{k: v for k, v in rt.turn.eligibility.items() if k not in ("delivered_at", "return_deadline", "message")},
            "delivered_on": templates.when(e.get("delivered_at")), "return_deadline": templates.when(e.get("return_deadline"))}
    await shopping.knowledge(rt, Route(route.intent, route.tier, {"question": "What is the return policy and how do returns work?"}), message,
                             document_types=["policy"], live={"eligibility_decided_by_dsports": live}, limit=3, verdict_first=True)
    rt.turn.fallback_text = authoritative
    if rt.turn.message_source != "ai":
        rt.turn.text = authoritative
    elif next_step and next_step not in rt.turn.text:
        rt.turn.text += next_step


async def _propose(rt: Runtime, tool: str, args: dict[str, Any]) -> bool:
    """Prepare from live data and, if possible, issue a confirmation token. Returns True when a proposal is waiting."""
    if rt.principal is None:
        return False
    try:
        proposal = await rt.services.registry.prepare(tool, args, rt.ctx)
    except ToolError as exc:
        rt.turn.text = rt.turn.fallback_text = f"I can't do that: {exc.message}"
        rt.turn.error_code = exc.code
        return False
    issued = await rt.services.confirmations.propose(customer_hash=rt.principal.customer_hash, tool=tool, args=proposal.args,
                                                     summary=proposal.summary, conversation_id=rt.conv.conversation_id)
    rt.conv.pending_nonce, rt.conv.pending_token = issued.nonce, issued.token
    rt.turn.confirmation_token = issued.token
    rt.turn.actions = [{"type": "confirm", "tool": tool, "label": "Confirm", "summary": proposal.summary,
                        "confirmation_token": issued.token, "expires_at": issued.expires_at.isoformat()}]
    rt.turn.extra_facts.append(proposal.facts)
    rt.turn.text = rt.turn.fallback_text = f"Please confirm: {proposal.summary}.\n{CONFIRM_HINT}"
    return True


async def return_create(rt: Runtime, route: Route) -> None:
    cand = rt.conv.slots.get("return_candidate")
    line = None
    if cand is None or route.slots.get("product_hint") or route.slots.get("order_number"):
        line, options = await find_purchase(rt, route)
        if options:
            return _ask_which(rt, options, "return")
        if line:
            cand = {"order_id": line["order_id"], "product_id": line["product_id"], "max_qty": line["quantity"]}
    if cand is None:
        rt.turn.text = rt.turn.fallback_text = ("Which product would you like to return? Tell me the product or the order number, "
                                                "and I'll check it first.")
        return
    quantity = route.slots.get("quantity") or cand.get("max_qty") or 1
    reason = route.slots.get("reason") or DEFAULT_RETURN_REASON
    await _propose(rt, "create_return", {"order_id": cand["order_id"], "product_id": cand["product_id"], "quantity": int(quantity), "reason": reason})


async def reorder(rt: Runtime, route: Route) -> None:
    line, options = await find_purchase(rt, route)
    if options:
        return _ask_which(rt, options, "buy again")
    if line is None:
        if rt.turn.status != "error":
            rt.turn.text = rt.turn.fallback_text = "I couldn't find that product in your past orders. Tell me which product you'd like to buy again."
        return
    args: dict[str, Any] = {"product_id": line["product_id"], "order_id": line["order_id"]}
    if route.slots.get("quantity"):
        args["quantity"] = int(route.slots["quantity"])
    await _propose(rt, "reorder_product", args)


# ------------------------------------------------------------------------------------------------ confirmation by chat

async def confirm_yes(rt: Runtime) -> None:
    token = rt.conv.pending_token
    if not token or rt.principal is None:
        rt.turn.text = rt.turn.fallback_text = "There's nothing waiting for your confirmation."
        return
    outcome = await rt.services.executor.confirm(rt.principal, token, rt.ctx)
    rt.conv.pending_nonce = rt.conv.pending_token = None
    apply_outcome(rt, outcome)


def apply_outcome(rt: Runtime, outcome) -> None:
    from app.support import flow as support_flow
    if not outcome.ok:
        rt.turn.status = "ok"
        rt.turn.error_code = outcome.code
        rt.turn.text = rt.turn.fallback_text = f"That didn't go through: {outcome.message}"
        return
    r = outcome.result or {}
    rt.turn.action_result = {"tool": outcome.tool, **r}
    rt.turn.extra_facts.append(outcome.facts or {})
    if support_flow.apply_support_outcome(rt, outcome):
        return
    if outcome.tool == "create_return":
        rt.turn.text = (f"Done. Your return request for {r['quantity']} x {r['product_name']} has been created (status {r['status']}). "
                        "A DSports team member will review it. Returns and refunds are separate steps, so a refund is handled afterwards.")
    elif outcome.tool == "reorder_product":
        a, c = r["added"], r["cart"]
        rt.turn.text = (f"Done. I added {a['quantity']} x {a['name']} to your cart. Your cart now has {c['total_items']} item(s), "
                        f"total {templates.inr(c['total_amount'])}. Nothing has been ordered or paid yet.")
    else:
        rt.turn.text = "Done."
    rt.turn.fallback_text = rt.turn.text


async def confirm_no(rt: Runtime) -> None:
    if rt.conv.pending_nonce and rt.principal:
        await rt.services.state.audit(customer_hash=rt.principal.customer_hash, action="CANCELLED", outcome="CANCELLED_BY_CUSTOMER",
                                      conversation_id=rt.conv.conversation_id)
    rt.conv.pending_nonce = rt.conv.pending_token = None
    rt.turn.text = rt.turn.fallback_text = "Okay, I've cancelled that. Nothing was changed."
