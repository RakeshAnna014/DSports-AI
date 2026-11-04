"""Product-contextual conversation: the customer is asking about ONE product ("Ask AI" on a card, or "tell me more about the MRF one").

The same Customer Agent, tools and RAG are used; this module only holds the shopping behaviour that needs a product in context:
alternatives ("cheaper", "lighter", "similar"), product facts ("how heavy is it?") and reviews. Every product fact comes from the
DSports `get_product` / `get_product_reviews` tools; the product id comes from conversation state (validated against DSports when it
was selected), never from model text.
"""
from __future__ import annotations

import re
from typing import Any

from app.agent import templates
from app.agent.runtime import Runtime
from app.router.intents import Route
from app.store.state import ConversationState

_SEARCH_KEYS = ("query", "sport", "category", "brand", "skill_level", "max_price", "min_price", "weight_pref", "age_group")


def select_product(conv: ConversationState, card: dict[str, Any]) -> None:
    """Make this DSports product the one "it"/"this" refers to. `card` is a get_product result (authoritative), never model text."""
    conv.selected_product = {"id": card["product_id"], "name": card["name"], "brand": card.get("brand"),
                             "category": card.get("category"), "sport": card.get("sport")}
    conv.comparison_ids = []


async def selected_card(rt: Runtime, product_id: str | None = None) -> dict[str, Any] | None:
    """The live DSports card of the product in context."""
    pid = product_id or (rt.conv.selected_product or {}).get("id")
    if not pid:
        return None
    result = await rt.call("get_product", {"product_id": pid})
    return result.data["product"] if result.ok else None


async def alternatives(rt: Runtime, route: Route, remember) -> dict[str, Any]:
    """"Is there something cheaper / lighter / similar?" about the product in context: a normal catalogue search for the same sport and
    category, then filtered against the product's LIVE price and weight so that "cheaper" really is cheaper."""
    base = await selected_card(rt, route.slots["alternative_to"])
    if base is None:
        rt.turn.text = rt.turn.fallback_text = "I can't find that product any more, so I can't look for alternatives to it."
        return {"slots": {}, "data": None}
    cheaper, lighter = bool(route.slots.get("cheaper")), bool(route.slots.get("lighter"))
    slots: dict[str, Any] = {k: v for k, v in {"sport": base.get("sport"), "category": base.get("category")}.items() if v}
    if cheaper and base.get("price"):
        slots["max_price"] = float(base["price"])
    if lighter:
        slots["weight_pref"] = "light"
    args = {k: v for k, v in slots.items() if k in _SEARCH_KEYS}
    result = await rt.call("search_products", {**args, "limit": 10})
    if not result.ok:
        rt.turn.status, rt.turn.error_code = "error", result.error_code
        rt.turn.text = rt.turn.fallback_text = "I couldn't search the catalogue just now. Please try again in a moment."
        return {"slots": slots, "data": None}
    data = result.data

    def keep(p: dict[str, Any]) -> bool:
        if p["product_id"] == base["product_id"]:
            return False
        if cheaper and not (p.get("price") is not None and base.get("price") is not None and p["price"] < base["price"]):
            return False
        if lighter and not (p.get("weight_g") is not None and base.get("weight_g") is not None and p["weight_g"] < base["weight_g"]):
            return False
        return True

    kept = [{k: v for k, v in p.items() if k != "over_budget"} for p in data["products"] if keep(p)][:5]
    data = {**data, "products": kept, "over_budget": False}
    rt.conv.slots["search"] = {**slots, "alternative_to": base["product_id"]}
    rt.turn.products = kept
    rt.turn.retrieval = {**data["retrieval"], "considered": data["considered"], "ranked": [p["name"] for p in kept]}
    what = "cheaper" if cheaper else "lighter" if lighter else "similar"
    if kept:
        remember(rt, kept)
        rt.conv.comparison_ids = []
        rt.turn.fallback_text = (f"Here are {what} alternatives to **{base['name']}** ({templates.inr(base.get('price'))}):\n"
                                 + "\n".join(f"{i}. **{p['name']}** - {templates.inr(p.get('price'))}, {templates.stock_text(p)}"
                                             for i, p in enumerate(kept, 1)))
    else:
        bound = f" than {templates.inr(base.get('price'))}" if cheaper else ""
        rt.turn.fallback_text = (f"There is no {what} {(base.get('category') or 'product').lower()}{bound} in the catalogue right now, "
                                 f"other than **{base['name']}** itself.")
    return {"slots": {**slots, "alternative_to": base["product_id"]}, "data": data}


_FACT_WORDS = (
    ("weight", re.compile(r"\b(weigh\w*|heavy|weight|lighter|lightness)\b", re.I)),
    ("material", re.compile(r"\b(material|made (of|from|out of)|willow|wood)\b", re.I)),
    ("price", re.compile(r"\b(price|cost|how much|mrp|discount)\b", re.I)),
    ("stock", re.compile(r"\b(in stock|available|availability|stock)\b", re.I)),
    ("dimensions", re.compile(r"\b(dimensions?|size|how (big|long|tall))\b", re.I)),
    ("rating", re.compile(r"\b(rating|rated)\b", re.I)),
)


async def detail(rt: Runtime, route: Route, message: str) -> None:
    """Facts about one product, read from DSports: answers the asked attribute directly, or summarises everything for "tell me more"."""
    card = await selected_card(rt, route.slots.get("product_id"))
    if card is None:
        rt.turn.text = rt.turn.fallback_text = "Which product do you mean? Choose one with \"Ask AI\" on its card, or search for it first."
        return
    select_product(rt.conv, card)
    asked = [name for name, pattern in _FACT_WORDS if pattern.search(message)] if not route.slots.get("overview") else []
    rt.turn.products = [card]
    rt.turn.text = rt.turn.fallback_text = templates.product_detail_text(card, asked)


async def reviews(rt: Runtime, route: Route) -> None:
    card = await selected_card(rt, route.slots.get("product_id"))
    if card is None:
        rt.turn.text = rt.turn.fallback_text = "Which product's reviews do you want? Choose one with \"Ask AI\" on its card, or search for it first."
        return
    select_product(rt.conv, card)
    result = await rt.call("get_product_reviews", {"product_id": card["product_id"], "limit": 5})
    if not result.ok:
        rt.turn.text = rt.turn.fallback_text = "I couldn't load the reviews just now. Please try again in a moment."
        rt.turn.error_code = result.error_code
        return
    data = result.data
    rt.turn.reviews = {"product_id": card["product_id"], "name": card["name"], "average_rating": data.get("average_rating"),
                       "total_reviews": data.get("total_reviews", 0), "reviews": data.get("reviews", [])}
    rt.turn.products = [card]
    rt.turn.text = rt.turn.fallback_text = templates.reviews_text(card["name"], rt.turn.reviews)
