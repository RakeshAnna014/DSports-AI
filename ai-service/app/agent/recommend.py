"""Explainable recommendations from real DSports data. No model call, nothing invented, nothing sport-specific in the code.

* A *kit* is data (`kits.json`, generated from the catalogue spec): for each sport an ordered list of roles (a cricket bat, gloves, a helmet;
  running shoes, socks, a hydration bottle; dumbbells, bands, a mat ...). Every product carries a `kitRole` attribute, so "complete your
  setup" is: the roles of the anchor's sport that the customer does not have yet, filled with live in-stock products.
* Cross-sport suggestions come from an explicit relation table and are only shown together with the reason the sports are related.
* Personalisation uses ONLY the customer's own orders (their token): sports, brands and categories bought, the price range they paid.
  A guest, or a customer with no orders, gets `personalized: false` and no "Recommended for you".
* Every item carries `reasons`; each is a statement that is true of the data. The bundle saving is the sum of the products' own list-price
  discounts (what DSports already shows on each card).
"""
from __future__ import annotations

import asyncio
import json
from collections import Counter
from pathlib import Path
from typing import Any

from app.agent.runtime import Runtime

_KITS = json.loads((Path(__file__).with_name("kits.json")).read_text(encoding="utf-8"))
KITS: dict[str, dict] = _KITS["kits"]
CROSS_SPORT: list[dict] = _KITS["crossSport"]
HISTORY_PRODUCTS = 6          # recent distinct purchases whose details are read to explain recommendations
MAX_HISTORY_SPORTS = 4
RECOMMEND_TOOL_BUDGET = 30
LEVELS = {"beginner", "intermediate", "advanced"}


def _inr(v: float) -> str:
    return f"₹{v:,.0f}"


def _role(card: dict[str, Any], sport: str | None) -> str | None:
    """The product's kit role, folded through the sport's aliases (a trail shoe fills the same slot as a road shoe)."""
    role = (card.get("attributes") or {}).get("kitRole")
    return KITS.get(sport or "", {}).get("aliases", {}).get(role, role) if role else None


def _sub(card: dict[str, Any]) -> str:
    return (card.get("attributes") or {}).get("subCategory") or str(card.get("category") or "product").lower()


async def _history(rt: Runtime) -> dict[str, Any]:
    """What the customer's own orders show. Empty for guests and for customers with no orders."""
    if rt.principal is None:
        return {}
    orders = await rt.call("get_orders", {"limit": 10})
    if not orders.ok or not orders.data["orders"]:
        return {}
    lines = [i for o in orders.data["orders"] for i in o.get("items", [])]            # newest order first
    if not lines:
        return {}
    recent: list[str] = list(dict.fromkeys(i["product_id"] for i in lines))
    results = await asyncio.gather(*(rt.call("get_product", {"product_id": pid}) for pid in recent[:HISTORY_PRODUCTS]))
    cards = [r.data["product"] for r in results if r.ok]
    prices = [float(i["unit_price"]) for i in lines if i.get("unit_price") is not None]
    sports = Counter(c["sport"] for c in cards if c.get("sport"))
    return {"purchased_ids": set(recent), "cards": cards, "sports": sports,
            "brands": {c["brand"] for c in cards if c.get("brand")}, "categories": {c["category"] for c in cards if c.get("category")},
            "roles": {(c.get("sport"), _role(c, c.get("sport"))) for c in cards}, "min_price": min(prices) if prices else None, "max_price": max(prices) if prices else None}


def _score(p: dict[str, Any], anchor: dict[str, Any], history: dict[str, Any]) -> float:
    """Deterministic preference among products that fill the same role: same level as the anchor, a brand the customer already bought,
    inside the price range they actually paid, then better value. Ties keep the search order."""
    score = 0.0
    skill = (anchor.get("attributes") or {}).get("skillLevel")
    if skill in LEVELS and (p.get("attributes") or {}).get("skillLevel") == skill:
        score += 2
    if any(c.get("brand") == p.get("brand") and c.get("sport") == p.get("sport") for c in history.get("cards", [])):
        score += 1
    lo, hi = history.get("min_price"), history.get("max_price")
    if lo is not None and hi is not None and p.get("price") is not None and lo * 0.6 <= p["price"] <= hi * 1.5:
        score += 1
    if p.get("mrp") and p.get("price") is not None and p["mrp"] > p["price"]:
        score += (p["mrp"] - p["price"]) / p["mrp"]
    return score


def _reasons(p: dict[str, Any], anchor: dict[str, Any], history: dict[str, Any], phrase: str, *, top_sport: str | None) -> list[str]:
    out = [f"Completes your {phrase}: {_a(_sub(p))} to go with {anchor['name']}"]
    skill = (anchor.get("attributes") or {}).get("skillLevel")
    if skill in LEVELS and (p.get("attributes") or {}).get("skillLevel") == skill:
        out.append(f"Suitable for {skill} level, like {anchor['name']}")
    bought = next((c for c in history.get("cards", []) if c.get("brand") and c["brand"] == p.get("brand") and c.get("sport") == p.get("sport")), None)
    if bought:
        out.append(f"You bought {bought['brand']} before ({bought['name']})")
    elif p.get("brand") and p.get("brand") == anchor.get("brand"):
        out.append(f"Same brand as {anchor['name']}")
    if top_sport and p.get("sport") == top_sport:
        out.append(f"Matches your preferred sport: {top_sport}")
    lo, hi = history.get("min_price"), history.get("max_price")
    if lo is not None and hi is not None and p.get("price") is not None and lo * 0.6 <= p["price"] <= hi * 1.5:
        out.append(f"Within your usual price range ({_inr(lo)}–{_inr(hi)})")
    if history and (p.get("sport"), _role(p, p.get("sport"))) not in history.get("roles", set()):
        out.append(f"You haven't bought {_a(_sub(p))} from DSports yet")
    if p.get("mrp") and p.get("price") is not None and p["mrp"] > p["price"]:
        out.append(f"{round((p['mrp'] - p['price']) / p['mrp'] * 100)}% off: you save {_inr(p['mrp'] - p['price'])}")
    return out


def _a(noun: str) -> str:
    """"a bat", "an arm guard", but plural nouns take no article: "batting pads", "dumbbells"."""
    if noun.lower().endswith("s") and not noun.lower().endswith(("ss", "us")):
        return noun
    return ("an " if noun[:1].lower() in "aeiou" else "a ") + noun


async def _fill(rt: Runtime, sport: str, role: str, category: str, anchor: dict[str, Any], history: dict[str, Any], skip: set[str],
                max_price: float | None = None) -> dict[str, Any] | None:
    args: dict[str, Any] = {"sport": sport, "category": category, "query": role.replace("-", " "), "limit": 10}
    if max_price is not None:
        args["max_price"] = max_price
    found = await rt.call("search_products", args)
    if not found.ok:
        return None
    candidates = [p for p in found.data["products"] if p["product_id"] not in skip and p.get("in_stock") is not False and _role(p, p.get("sport")) == role
                  and not p.get("over_budget") and (max_price is None or (p.get("price") is not None and p["price"] <= max_price))]
    if not candidates:
        return None
    return max(candidates, key=lambda p: (_score(p, anchor, history), -candidates.index(p)))


async def recommend(rt: Runtime, anchor_ids: list[str], limit: int = 4, exclude_ids: list[str] | None = None) -> dict[str, Any]:
    rt.ctx.max_calls = max(rt.ctx.max_calls, RECOMMEND_TOOL_BUDGET)
    history = await _history(rt)
    anchors = [c.data["product"] for c in await asyncio.gather(*(rt.call("get_product", {"product_id": a}) for a in anchor_ids[:3])) if c.ok]
    why: list[str] = []
    if anchors:
        title = "Complete your setup"
        why.append("Based on what is in your cart or selection")
    elif history.get("cards"):
        # One anchor per sport the customer has bought, the sport they buy most first, so a multi-sport customer is not shown one sport only.
        seen: set[str] = set()
        ordered = sorted(history["cards"], key=lambda c: -history["sports"][c["sport"]])
        anchors = [c for c in ordered if c.get("sport") and not (c["sport"] in seen or seen.add(c["sport"]))][:MAX_HISTORY_SPORTS]
        title = "Recommended for you"
        why.append("Based on your previous purchases: " + ", ".join(c["name"] for c in anchors))
    else:
        return {"title": "Recommended for you", "personalized": False, "because": [], "items": [], "bundle": None}

    top_sport = history["sports"].most_common(1)[0][0] if history.get("sports") else None
    skip = set(exclude_ids or []) | {a["product_id"] for a in anchors} | set(history.get("purchased_ids", set()))
    have = {(a.get("sport"), _role(a, a.get("sport"))) for a in anchors} | set(history.get("roles", set()))
    queue: list[tuple[dict, str, str, str]] = []        # (anchor, role, category, phrase), round-robin across the anchors' sports
    per_anchor = []
    for a in anchors:
        kit = KITS.get(a.get("sport") or "")
        if not kit:
            continue
        per_anchor.append([(a, role, cat, kit["phrase"]) for role, cat in kit["roles"] if (a.get("sport"), role) not in have])
    while any(per_anchor) and len(queue) < limit * 3:
        for lst in per_anchor:
            if lst:
                queue.append(lst.pop(0))

    items: list[dict[str, Any]] = []
    for anchor, role, category, phrase in queue:
        if len(items) >= limit:
            break
        if any(i["_role"] == (anchor.get("sport"), role) for i in items):
            continue
        pick = await _fill(rt, anchor["sport"], role, category, anchor, history, skip)
        if pick:
            skip.add(pick["product_id"])
            items.append({**pick, "reasons": _reasons(pick, anchor, history, phrase, top_sport=top_sport), "anchor": anchor["name"], "_role": (anchor.get("sport"), role)})

    # Cross-sport: only from an explicit relation, only when there is room, always with its own explanation.
    if len(items) < limit:
        for anchor in anchors:
            role = _role(anchor, anchor.get("sport"))
            for rel in CROSS_SPORT:
                if len(items) >= limit:
                    break
                if rel["fromSport"] != anchor.get("sport") or rel["fromRole"] != role or (rel["toSport"], rel["toRole"]) in have:
                    continue
                cat = next((c for r, c in KITS.get(rel["toSport"], {}).get("roles", []) if r == rel["toRole"]), None)
                pick = cat and await _fill(rt, rel["toSport"], rel["toRole"], cat, anchor, history, skip)
                if pick:
                    skip.add(pick["product_id"])
                    items.append({**pick, "reasons": [rel["why"], *_reasons(pick, anchor, history, KITS[rel["toSport"]]["phrase"], top_sport=top_sport)[1:3]],
                                  "anchor": anchor["name"], "cross_sport": True, "_role": (rel["toSport"], rel["toRole"])})
    for i in items:
        i.pop("_role", None)
    if history:
        why.append(f"You usually shop between {_inr(history['min_price'])} and {_inr(history['max_price'])}"
                   if history.get("min_price") is not None and history.get("max_price") is not None else "Using your order history")
    bundle = None
    if items and anchors:
        group = [*anchors, *items]
        priced = [p for p in group if p.get("price") is not None]
        savings = sum(max(0.0, (p["mrp"] - p["price"])) for p in priced if p.get("mrp"))
        if len(priced) >= 2:
            bundle = {"products": [{"product_id": p["product_id"], "name": p["name"]} for p in priced],
                      "total": round(sum(p["price"] for p in priced), 2), "savings": round(savings, 2) if savings > 0 else None}
    return {"title": title, "personalized": bool(history), "because": why, "items": items, "bundle": bundle}


# ------------------------------------------------------------------------------------------------ chat handlers (deterministic, no model)

def _line(i: int, p: dict[str, Any]) -> str:
    return f"{i}. **{p['name']}** - {_inr(p['price'])}" + (f" ({p['reasons'][0]})" if p.get("reasons") else "")


async def recommend_for_chat(rt: Runtime, route) -> None:
    """"Recommend something for me" / "what goes with this?": the product in context, else the last shown, else the customer's own history."""
    conv = rt.conv
    anchor_ids = [conv.selected_product["id"]] if conv.selected_product else [conv.last_products[0]["id"]] if conv.last_products else []
    if route.slots.get("scenario") and conv.selected_product:
        return await _scenario_for_chat(rt, route, conv.selected_product["id"])
    rec = await recommend(rt, anchor_ids, 4)
    if not rec["items"]:
        rt.turn.text = rt.turn.fallback_text = (
            "Sign in and I can recommend products from your own order history. Meanwhile, tell me a sport or ask me to build a kit, for example \"complete my cricket kit\"."
            if rt.principal is None and not anchor_ids else
            "I don't have enough to recommend from yet. Tell me what you play and your budget, or pick a product with Ask AI.")
        return
    rt.turn.recommendations = rec
    rt.turn.text = rt.turn.fallback_text = "\n".join([f"{rec['title']}:", *(_line(i, p) for i, p in enumerate(rec["items"], 1))])


async def _scenario_for_chat(rt: Runtime, route, product_id: str) -> None:
    """The scenario was chosen by rules (router); prices, stock, the budget band and the reasons all come from the pipeline, never from a model."""
    from app.errors import ApiException
    from app.reco.engine import RecoRequest, recommend_with_scenario
    from app.reco.policy import Scenario
    try:
        rec = await recommend_with_scenario(rt, RecoRequest(reference_product_id=product_id, scenario=Scenario(route.slots["scenario"]),
                                                            budget=float(route.slots["budget"]) if route.slots.get("budget") else None, limit=4))
    except ApiException as e:
        rt.turn.text = rt.turn.fallback_text = f"I could not prepare those recommendations: {e.message}"
        return
    lines = [f"{rec['title']}:"]
    if rec["budget"]["applied"]["label"]:
        lines.append(rec["budget"]["applied"]["label"])
    if rec["items"]:
        rt.turn.recommendations = rec
        lines += [_line(i, p) for i, p in enumerate(rec["items"], 1)]
    else:
        lines.append(rec["message"] or "Nothing in stock matches that right now.")
    rt.turn.text = rt.turn.fallback_text = "\n".join(lines)


async def build_kit_for_chat(rt: Runtime, route) -> None:
    """"I want to build a home gym under 10000": walk the sport's kit in order and take the best in-stock product for each role that still fits the budget."""
    conv = rt.conv
    sport = route.slots.get("sport") or (conv.selected_product or {}).get("sport") or (conv.slots.get("search") or {}).get("sport")
    budget = route.slots.get("max_price")
    kit = KITS.get(sport or "")
    if not kit:
        rt.turn.text = rt.turn.fallback_text = "Which sport is the kit for? For example \"complete my cricket kit\" or \"build a home gym under ₹10,000\"."
        return
    rt.ctx.max_calls = max(rt.ctx.max_calls, RECOMMEND_TOOL_BUDGET)
    anchor = {"name": f"your {kit['phrase']}", "sport": sport, "attributes": {}}
    remaining, skip, items = (float(budget) if budget else None), set(), []
    for role, category in kit["roles"]:
        if len(items) >= 7:
            break
        if remaining is not None and remaining <= 0:
            break
        pick = await _fill(rt, sport, role, category, anchor, {}, skip, max_price=remaining)
        if pick:
            skip.add(pick["product_id"])
            reasons = [f"Part of your {kit['phrase']}: {_a(_sub(pick))}"]
            if pick.get("mrp") and pick.get("price") is not None and pick["mrp"] > pick["price"]:
                reasons.append(f"{round((pick['mrp'] - pick['price']) / pick['mrp'] * 100)}% off: you save {_inr(pick['mrp'] - pick['price'])}")
            items.append({**pick, "reasons": reasons})
            if remaining is not None:
                remaining -= pick["price"]
    if not items:
        rt.turn.text = rt.turn.fallback_text = f"I couldn't put together a {sport} set-up within that budget from what is in stock."
        return
    total = round(sum(p["price"] for p in items), 2)
    savings = round(sum(max(0.0, p["mrp"] - p["price"]) for p in items if p.get("mrp")), 2)
    because = [f"A {kit['phrase']} from the DSports {sport} range"] + ([f"Budget {_inr(budget)}: {_inr(budget - total)} left"] if budget else [])
    rt.turn.recommendations = {"title": f"Your {kit['phrase']}" + (f" under {_inr(budget)}" if budget else ""), "personalized": False, "because": because, "items": items,
                               "bundle": {"products": [{"product_id": p["product_id"], "name": p["name"]} for p in items], "total": total, "savings": savings or None,
                                          "budget": float(budget) if budget else None}}
    rt.turn.text = rt.turn.fallback_text = "\n".join([f"{rt.turn.recommendations['title']}:", *(_line(i, p) for i, p in enumerate(items, 1)),
                                                    f"Total {_inr(total)}" + (f", you save {_inr(savings)} against list prices" if savings else "")])
