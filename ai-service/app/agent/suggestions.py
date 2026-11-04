"""Contextual follow-up actions shown under a response. All deterministic: they depend on what the response contains, never on the model."""
from __future__ import annotations

from app.agent.offers import add_action, add_chip
from app.agent.runtime import Runtime
from app.router.intents import Intent


def build_suggestions(rt: Runtime, intent: str) -> None:
    t, conv = rt.turn, rt.conv
    if t.status != "ok" or t.suggestions or t.selection or t.actions:
        return
    if t.eligibility:
        e = t.eligibility
        if e.get("eligible") and conv.slots.get("return_candidate"):
            add_action(rt, "Return this item", Intent.RETURN_CREATE, {})
        return
    if t.tracking:
        if t.tracking.get("order_id"):
            add_action(rt, "Return an item from this order", Intent.RETURN_CHECK, {"order_id": t.tracking["order_id"]})
            add_action(rt, "Reorder", Intent.REORDER, {"order_id": t.tracking["order_id"]})
        return
    if t.orders and len(t.orders) == 1 and t.orders[0].get("order_id"):
        oid = t.orders[0]["order_id"]
        add_action(rt, "Track this order", Intent.TRACK_ORDER, {"order_id": oid})
        add_action(rt, "Return an item", Intent.RETURN_CHECK, {"order_id": oid})
        add_action(rt, "Reorder", Intent.REORDER, {"order_id": oid})
        return
    if t.comparison:
        add_chip(rt, "Which one is lighter?")
        add_chip(rt, "Which one is better for a beginner?")
        add_chip(rt, "Show cheaper alternatives", "Is there a cheaper alternative?" if conv.selected_product else "Show me something cheaper")
        return
    if t.reviews or (intent == Intent.PRODUCT_DETAIL.value and t.products):
        add_chip(rt, "Show reviews")
        add_chip(rt, "Is there a cheaper alternative?")
        add_chip(rt, "Is it suitable for a beginner?")
        return
    if t.products:
        if len(t.products) >= 2:
            add_chip(rt, "Compare these")
            add_chip(rt, "Which one is best for a beginner?")
        add_chip(rt, "Show cheaper ones", "Show me something cheaper")
        add_chip(rt, "Show lighter ones", "Show lighter options")
