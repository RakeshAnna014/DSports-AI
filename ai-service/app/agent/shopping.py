"""Shopping subgraph nodes: discovery, refinement, comparison/advice and product knowledge.

Decisions (what to search, which products, in what order, at what price) are made by rules and live data. The model is only
asked to explain a result that already exists, and falls back to a deterministic text when it cannot.
"""
from __future__ import annotations

import asyncio
import logging
import math
import re
from typing import Any

from app.agent import product_context, prompts, templates
from app.agent.runtime import BudgetExceeded, DISCLAIMER_AI, Runtime
from app.llm.base import LLMError
from app.router.intents import Intent, Route
from app.router.extract import extract_shopping_slots
from app.shop.ranking import Preferences, rank
from app.shop.catalog import ProductFacts
from app.sourcing import demand

log = logging.getLogger(__name__)
SEARCH_KEYS = ("query", "sport", "category", "brand", "skill_level", "max_price", "min_price", "weight_pref", "age_group")
PRICE_DOWN, PRICE_UP = 0.8, 1.25   # "too expensive" lowers the ceiling to 80% of the reference price; "higher" raises it by 25%
MAX_COMPARE = 5
HINT_WITHIN = 2.0   # the "lowest-priced match" hint is only given when it is within 2x of the budget; beyond that it is noise, not help
LABELS = {"skillLevel": "Skill level", "weightClass": "Weight class", "ageGroup": "Age group", "playFormat": "Play format",
          "ballType": "Ball type", "material": "Material", "surface": "Surface"}


# ------------------------------------------------------------------------------------------------ slots & state

def merge_search_slots(route: Route, prior: dict[str, Any], last_products: list[dict[str, Any]]) -> dict[str, Any]:
    """New search: only what was just said. Refinement: the previous search plus what changed."""
    fresh = {k: v for k, v in route.slots.items() if k in SEARCH_KEYS and v not in (None, "")}
    if route.intent != Intent.REFINE_SEARCH or not prior:
        return fresh
    merged = {**prior, **fresh}
    feedback = route.slots.get("price_feedback")
    if feedback and "max_price" not in fresh:
        reference = prior.get("max_price") or max((p.get("price") or 0 for p in last_products), default=0)
        if reference:
            factor = PRICE_DOWN if feedback == "lower" else PRICE_UP
            merged["max_price"] = float(math.floor(reference * factor)) if feedback == "lower" else float(math.ceil(reference * factor))
            if feedback == "higher":
                merged.pop("min_price", None)
    merged.pop("query", None) if fresh.keys() & {"category", "sport"} else None
    return merged


def remember_products(rt: Runtime, cards: list[dict[str, Any]]) -> None:
    conv = rt.conv
    conv.last_products = [{"id": c["product_id"], "name": c["name"], "price": c.get("price"), "weight_g": c.get("weight_g"),
                           "category": c.get("category"), "sport": c.get("sport"),
                           "skill": (c.get("attributes") or {}).get("skillLevel")} for c in cards[:MAX_COMPARE]]
    conv.candidate_product_ids = [c["product_id"] for c in cards]


# ------------------------------------------------------------------------------------------------ nodes

async def search(rt: Runtime, route: Route) -> dict[str, Any]:
    if route.slots.get("alternative_to"):
        return await product_context.alternatives(rt, route, remember_products)
    slots = merge_search_slots(route, rt.conv.slots.get("search", {}), rt.conv.last_products)
    if not slots:
        rt.turn.text = rt.turn.fallback_text = "What are you looking for? For example a sport, a type of product, a budget or a skill level."
        return {"slots": {}, "data": None}
    args = {k: v for k, v in slots.items() if k in SEARCH_KEYS}
    result = await rt.call("search_products", args)
    if not result.ok:
        rt.turn.status, rt.turn.error_code = "error", result.error_code
        rt.turn.text = rt.turn.fallback_text = "I couldn't search the catalogue just now. Please try again in a moment."
        return {"slots": slots, "data": None}
    data = result.data
    rt.conv.slots["search"] = slots
    rt.turn.products = data["products"]
    rt.turn.retrieval = {**data["retrieval"], "considered": data["considered"],
                         "ranked": [p["name"] for p in data["products"]], "retrieved": data["retrieval"].get("names", [])}
    if data["products"]:   # an empty refinement must not erase what the customer last saw ("which one is better?" refers to that)
        remember_products(rt, data["products"])
    rt.turn.fallback_text = templates.products_text(data, slots)
    await demand.record_search_outcome(rt.services, rt.principal, slots, data["products"], rt.user_message)
    if not data["products"] and slots.get("max_price") is not None:
        await _cheapest_above_budget(rt, args, slots)
    return {"slots": slots, "data": data}


async def _cheapest_above_budget(rt: Runtime, args: dict[str, Any], slots: dict[str, Any]) -> None:
    """Nothing fits the budget and nothing is close: say what the cheapest match actually costs (live), instead of a bare 'not found'."""
    wider = await rt.call("search_products", {**{k: v for k, v in args.items() if k not in ("max_price", "min_price")}, "limit": 5})
    if not wider.ok or not wider.data["products"]:
        return
    cheapest = min((p for p in wider.data["products"] if p.get("price") is not None), key=lambda p: p["price"], default=None)
    if cheapest is None or cheapest["price"] > slots["max_price"] * HINT_WITHIN:
        return
    rt.turn.products = [{**cheapest, "over_budget": True}]
    rt.turn.fallback_text = (f"Nothing matches within {templates.inr(slots['max_price'])}. The lowest-priced match is "
                             f"**{cheapest['name']}** at {templates.inr(cheapest['price'])}, {templates.stock_text(cheapest)}, which is above your budget.")


async def explain_search(rt: Runtime, message: str, slots: dict[str, Any], data: dict[str, Any] | None) -> None:
    if data is None or not data["products"]:
        rt.turn.text = rt.turn.fallback_text or rt.turn.text
        return
    await _llm_text(rt, prompts.explain_search(message, slots, data), "explain_search", max_tokens=420)


async def _llm_text(rt: Runtime, prompt: str, intent: str, *, max_tokens: int = 400) -> bool:
    """Ask the model for the explanation; on any failure keep the deterministic text and say so. Returns True if AI text was used."""
    try:
        result = await rt.ask(prompt, tier="reasoning", system=prompts.SYSTEM, intent=intent, max_tokens=max_tokens)
    except (LLMError, BudgetExceeded) as exc:
        rt.turn.text = rt.turn.fallback_text
        code = exc.code if isinstance(exc, LLMError) else "TOKEN_BUDGET"
        rt.turn.disclaimers.append("The AI explanation is unavailable right now; you are seeing the verified data only.")
        rt.turn.error_code = rt.turn.error_code or code
        return False
    text = result.text.strip()
    if not text:
        rt.turn.text = rt.turn.fallback_text
        return False
    rt.turn.text, rt.turn.message_source = text, "ai"
    rt.turn.disclaimers.append(DISCLAIMER_AI)
    return True


# ------------------------------------------------------------------------------------------------ compare / advice

def _tokens(text: str) -> set[str]:
    return {w for w in re.findall(r"[a-z0-9]+", text.lower()) if len(w) > 1}


async def resolve_product_ids(rt: Runtime, route: Route) -> list[str]:
    """Which products the customer means: ordinals ("the first and third"), names, or by default everything just shown.
    A name that is not among the products just shown is looked up through the same search tool (live, ranked, no model)."""
    last = rt.conv.last_products
    picked: list[str] = []
    for n in route.slots.get("ordinals") or []:
        idx = len(last) if n == -1 else n
        if 1 <= idx <= len(last):
            picked.append(last[idx - 1]["id"])
    for ref in route.slots.get("product_refs") or []:
        if isinstance(ref, int) and 1 <= ref <= len(last):
            picked.append(last[ref - 1]["id"])
            continue
        if not isinstance(ref, str):
            continue
        words = _tokens(ref)
        best = max(last, key=lambda p: len(words & _tokens(p["name"])), default=None)
        if best and len(words & _tokens(best["name"])) >= min(2, len(words)):
            picked.append(best["id"])
            continue
        found = await rt.call("search_products", {"query": ref, "limit": 1, "in_stock_only": False})
        if found.ok and found.data["products"]:
            top = found.data["products"][0]
            if len(words & _tokens(top["name"])) >= min(2, len(words)):
                picked.append(top["product_id"])
    if route.slots.get("with_selected") and rt.conv.selected_product:
        picked.insert(0, rt.conv.selected_product["id"])
    if not picked:
        picked = list(rt.conv.comparison_ids) or [p["id"] for p in last]
    return list(dict.fromkeys(picked))[:MAX_COMPARE]


def build_comparison(cards: list[dict[str, Any]]) -> dict[str, Any]:
    na = templates.NOT_AVAILABLE

    def row(attribute: str, values: list[str]) -> dict[str, Any]:
        return {"attribute": attribute, "values": values}

    rows = [
        row("Price", [templates.inr(c.get("price")) if c.get("price") is not None else na for c in cards]),
        row("List price (MRP)", [templates.inr(c.get("mrp")) if c.get("mrp") is not None else na for c in cards]),
        row("Stock", [templates.stock_text(c) for c in cards]),
        row("Brand", [c.get("brand") or na for c in cards]),
        row("Category", [c.get("category") or na for c in cards]),
        row("Weight", [templates.weight_text(c.get("weight_g")) for c in cards]),
        row("Dimensions", [c.get("dimensions") or na for c in cards]),
        row("Rating", [f"{c['rating']['average']:.1f}/5 ({c['rating']['count']} reviews)" if c.get("rating") else "No reviews yet" for c in cards]),
    ]
    keys = sorted({k for c in cards for k in (c.get("attributes") or {})}, key=lambda k: (list(LABELS).index(k) if k in LABELS else 99, k))
    for k in keys:
        rows.append(row(LABELS.get(k, k), [str((c.get("attributes") or {}).get(k, na)).replace("_", " ") for c in cards]))
    return {"products": [{"product_id": c["product_id"], "name": c["name"], "image_url": c.get("image_url")} for c in cards], "rows": rows}


async def compare(rt: Runtime, route: Route, message: str) -> dict[str, Any]:
    ids = await resolve_product_ids(rt, route)
    if not ids:
        rt.turn.text = rt.turn.fallback_text = ("Which products would you like me to look at? Ask me to find some first, for example "
                                                "\"show me cricket bats under ₹8,000\", and then I can compare them.")
        return {"table": None}
    results = await asyncio.gather(*(rt.call("get_product", {"product_id": pid}) for pid in ids))
    cards = [r.data["product"] for r in results if r.ok]
    if not cards:
        rt.turn.text = rt.turn.fallback_text = "Those products are not available right now, so I can't compare them."
        return {"table": None}
    table = build_comparison(cards)
    rt.turn.comparison, rt.turn.products = table, cards
    rt.conv.comparison_ids = [c["product_id"] for c in cards] if len(cards) >= 2 else []
    if any(c["product_id"] not in set(rt.conv.candidate_product_ids) for c in cards):   # comparing a subset must not shrink what the customer last saw
        remember_products(rt, cards)
    prefs = {k: v for k, v in {**rt.conv.slots.get("search", {}), **{k: v for k, v in route.slots.items() if k in SEARCH_KEYS}}.items()
             if k in ("skill_level", "weight_pref", "max_price", "min_price", "sport", "category") and v}
    rt.turn.fallback_text = templates.comparison_text(table) + "\n" + _deterministic_pick(cards, prefs)
    if len(cards) < 2 and route.intent == Intent.COMPARE:
        rt.turn.fallback_text = "I need at least two products to compare. Here is what I know about it:\n" + templates.comparison_text(table)
    used_ai = await _llm_text(rt, prompts.advise(message, table, prefs), "advise", max_tokens=520)
    missing = [r["attribute"] for r in table["rows"] if r["values"] and all(v == templates.NOT_AVAILABLE for v in r["values"])]
    if used_ai and missing:   # stated by code from the table, so it never depends on what the model chose to mention
        rt.turn.text += "\n\n" + templates.NOT_AVAILABLE + ": " + ", ".join(missing) + "."
    return {"table": table}


def _deterministic_pick(cards: list[dict[str, Any]], prefs: dict[str, Any]) -> str:
    """Recommendation from the same deterministic ranking used for search, so the no-AI answer is still useful."""
    facts = []
    for c in cards:
        from app.shop.catalog import Price, Rating, Stock
        facts.append((ProductFacts(product_id=c["product_id"], name=c["name"], brand=c.get("brand"), category=c.get("category"),
                                   sport=c.get("sport"), attributes=c.get("attributes") or {}, weight_g=c.get("weight_g"),
                                   price=Price(selling_price=c["price"]) if c.get("price") is not None else None,
                                   stock=Stock(available_quantity=c.get("available_quantity", 0), status=c.get("stock_status", "IN_STOCK")) if "in_stock" in c else None,
                                   rating=Rating(**c["rating"]) if c.get("rating") else None), 1.0))
    best = rank(facts, Preferences(skill_level=prefs.get("skill_level"), weight_pref=prefs.get("weight_pref"),
                                   max_price=prefs.get("max_price"), min_price=prefs.get("min_price")))
    if not best:
        return ""
    top = best[0]
    return f"Best match for your stated needs by the shop's ranking rules: **{top.product.name}**."


# ------------------------------------------------------------------------------------------------ knowledge

async def knowledge(rt: Runtime, route: Route, message: str, *, document_types: list[str] | None = None,
                    live: dict[str, Any] | None = None, limit: int | None = None, verdict_first: bool = False) -> dict[str, Any] | None:
    """Retrieve cited passages and answer from them (or say there is not enough information)."""
    args: dict[str, Any] = {"question": route.slots.get("question") or message}
    if limit:
        args["limit"] = limit
    if document_types:
        args["document_types"] = document_types
    last = rt.conv.last_products
    if rt.conv.selected_product and route.slots.get("about_selected"):
        args["product_id"] = rt.conv.selected_product["id"]
    elif len(last) == 1:
        args["product_id"] = last[0]["id"]
    category = route.slots.get("category") or (last[0].get("category") if last and len({p.get("category") for p in last}) == 1 else None)
    sport = route.slots.get("sport") or (last[0].get("sport") if last and len({p.get("sport") for p in last}) == 1 else None)
    if category:
        args["category"] = category
    if sport:
        args["sport"] = sport
    result = await rt.call("get_product_knowledge", args)
    if not result.ok:
        rt.turn.text = rt.turn.fallback_text = "I can't reach the knowledge base right now, so I can't answer that reliably. Please try again shortly."
        rt.turn.status, rt.turn.error_code = "error", result.error_code
        return None
    data = result.data
    chunks = data["chunks"]
    rt.turn.retrieval = {**data["retrieval"], "sources": [c["source"] for c in chunks], "pages": [c["page"] for c in chunks]}
    if not chunks:
        rt.turn.text = rt.turn.fallback_text = NOT_ENOUGH
        return data
    top = chunks[0]
    rt.turn.fallback_text = (f"From {top['title'] or top['source']} (page {top['page']}): " + top["text"][:600].rstrip()
                             + ("..." if len(top["text"]) > 600 else ""))
    rt.turn.sources = [_source(top)]
    try:
        result = await rt.ask(prompts.answer_from_sources(args["question"], chunks, live, verdict_first=verdict_first), tier="reasoning", system=prompts.SYSTEM,
                              intent="rag_answer", max_tokens=520)
    except (LLMError, BudgetExceeded) as exc:
        rt.turn.text = rt.turn.fallback_text
        rt.turn.disclaimers.append("The AI summary is unavailable right now; this is the most relevant passage from our knowledge base.")
        rt.turn.error_code = exc.code if isinstance(exc, LLMError) else "TOKEN_BUDGET"
        return data
    answer = result.text.strip()
    if prompts.INSUFFICIENT in answer:
        rt.turn.text = rt.turn.fallback_text = NOT_ENOUGH
        rt.turn.sources = []
        return data
    cited = sorted({int(n) for n in re.findall(r"\[(\d+)\]", answer) if 1 <= int(n) <= len(chunks)})
    if not cited:  # an uncited answer cannot be checked against its sources: do not present it as grounded
        rt.turn.text = rt.turn.fallback_text
        rt.turn.disclaimers.append("I could not tie the AI answer to a source, so I am showing the most relevant passage instead.")
        return data
    rt.turn.text, rt.turn.message_source = answer, "ai"
    rt.turn.sources = [_source(chunks[n - 1]) for n in cited]
    rt.turn.disclaimers.append(DISCLAIMER_AI)
    return data


NOT_ENOUGH = "I couldn't find enough information in the DSports knowledge base to answer that reliably."


def _source(c: dict[str, Any]) -> dict[str, Any]:
    return {"source": c["source"], "title": c.get("title"), "page": c.get("page"), "document_type": c.get("document_type"),
            "chunk_id": c["chunk_id"], "snippet": (c["text"][:200].rstrip() + "...") if len(c["text"]) > 200 else c["text"]}
