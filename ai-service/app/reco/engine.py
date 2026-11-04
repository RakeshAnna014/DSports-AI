"""Budget- and context-aware recommendation pipeline.

scenario -> reference product -> profile and history -> candidates (live DSports data) -> hard constraints -> budget band (with labelled
fallbacks) -> scoring -> diversity -> ranked results with factual reasons.

No model is called anywhere in this pipeline: prices, stock, discounts, compatibility and the budget band are decided by code from data the
Java backend returned, so an LLM outage cannot change or break a recommendation (`meta.llm_tokens` is always 0).
"""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from dataclasses import dataclass
from typing import Any

from app.agent.recommend import KITS, _a, _history, _inr, _role, _sub
from app.agent.runtime import Runtime
from app.errors import ApiException
from app.obs import metrics
from app.reco.policy import (Band, BudgetPolicy, RecoContext, Scenario, bands_for, diversify, effective_price, hard_violation, rank,
                             widest)

log = logging.getLogger(__name__)

RECO_TOOL_BUDGET = 30
MAX_ROLES = 10          # every role of a kit; the cheap accessories a low ceiling targets sit at the end of the list
MAX_SPORTS = 3
LEVELS = {"beginner", "intermediate", "advanced"}


@dataclass(frozen=True)
class RecoRequest:
    reference_product_id: str | None = None
    scenario: Scenario | None = None
    profile_id: str | None = None
    budget: float | None = None
    limit: int = 4


def _uuid(raw: str | None, what: str) -> str | None:
    if not raw:
        return None
    try:
        return str(uuid.UUID(raw))
    except ValueError:
        raise ApiException(400, "VALIDATION_ERROR", f"{what} is not a valid id")


def _availability(card: dict[str, Any]) -> str:
    qty = card.get("available_quantity")
    if isinstance(qty, int) and 0 < qty <= 5:
        return f"Only {qty} left"
    return "In stock"


def _primary_reason(scn: Scenario, c: dict[str, Any], ref: dict[str, Any] | None) -> str:
    if scn is Scenario.COMPLEMENTARY and ref:
        return f"Goes with {ref['name']}: {_a(_sub(c))}"
    if scn is Scenario.SIMILAR and ref:
        return f"A comparable {_sub(c)} to {ref['name']}"
    if scn is Scenario.UPGRADE and ref:
        return f"A premium alternative to {ref['name']}"
    if scn is Scenario.FREQUENTLY_BOUGHT_TOGETHER and ref:
        return f"Frequently bought with {ref['name']}"
    return f"Picked for you: {_a(_sub(c))}"


def _item(c: dict[str, Any], scn: Scenario, ref: dict[str, Any] | None, band: Band) -> dict[str, Any]:
    price, mrp = effective_price(c), c.get("mrp")
    discount = round((mrp - price) / mrp * 100) if isinstance(mrp, (int, float)) and price and mrp > price else None
    out = {k: v for k, v in c.items() if not k.startswith("_") and k not in ("match_factors", "rank_score", "over_budget")}
    out.update(reasons=[_primary_reason(scn, c, ref), *c["_why"]], original_price=mrp if discount else None, discount_pct=discount,
               availability=_availability(c), scenario=scn.value, budget_note=band.label or None)
    return out


# ------------------------------------------------------------------------------------------------ candidate gathering

async def _search(rt: Runtime, **args: Any) -> list[dict[str, Any]]:
    found = await rt.call("search_products", {k: v for k, v in args.items() if v is not None})
    return list(found.data["products"]) if found.ok else []


async def _gather_complementary(rt: Runtime, ref: dict[str, Any], ceiling: float | None, age: str | None) -> list[dict[str, Any]]:
    kit = KITS.get(ref.get("sport") or "")
    if not kit:
        return []
    ref_role = _role(ref, ref.get("sport"))
    roles = [(r, c) for r, c in kit["roles"] if r != ref_role][:MAX_ROLES]
    pools = await asyncio.gather(*(_search(rt, sport=ref["sport"], category=c, query=r.replace("-", " "), max_price=ceiling, age_group=age, limit=10) for r, c in roles))
    return [p for pool in pools for p in pool]


async def _gather_comparable(rt: Runtime, ref: dict[str, Any], low: float | None, high: float | None, age: str | None) -> list[dict[str, Any]]:
    return await _search(rt, sport=ref.get("sport"), category=ref.get("category"), min_price=low, max_price=high, age_group=age, limit=10)


async def _gather_personalized(rt: Runtime, ctx: RecoContext, band: Band) -> list[dict[str, Any]]:
    prefs = ctx.prefs
    sports = list(prefs.get("sports") or []) or sorted(ctx.history_sports)
    categories = list(prefs.get("categories") or [])[:2] or [None]
    level = prefs.get("usage_level")
    level = level.lower() if isinstance(level, str) and level.lower() in LEVELS else None
    age = "junior" if ctx.child else None            # ask the catalogue for junior products first so they are not crowded out of the top 10
    jobs = [_search(rt, sport=s, category=c, skill_level=level, min_price=band.low, max_price=band.high, age_group=age, limit=10)
            for s in sports[:MAX_SPORTS] for c in categories]
    if not jobs:
        return []
    return [p for pool in await asyncio.gather(*jobs) for p in pool]


# ------------------------------------------------------------------------------------------------ the pipeline

async def recommend_with_scenario(rt: Runtime, req: RecoRequest) -> dict[str, Any]:
    started = time.perf_counter()
    rt.ctx.max_calls = max(rt.ctx.max_calls, RECO_TOOL_BUDGET)
    policy = BudgetPolicy.from_settings(rt.services.settings)
    ref_id, profile_id = _uuid(req.reference_product_id, "referenceProductId"), _uuid(req.profile_id, "profileId")
    scenario = req.scenario or (Scenario.COMPLEMENTARY if ref_id else Scenario.PERSONALIZED)
    if scenario is not Scenario.PERSONALIZED and not ref_id:
        raise ApiException(400, "VALIDATION_ERROR", f"The {scenario.value} scenario needs referenceProductId")
    if req.budget is not None and req.budget <= 0:
        raise ApiException(400, "VALIDATION_ERROR", "budget must be above zero")
    limit = max(1, min(req.limit, 12))

    ref: dict[str, Any] | None = None
    if ref_id:
        got = await rt.call("get_product", {"product_id": ref_id})
        if not got.ok:
            raise ApiException(404, "NOT_FOUND", "That product is not available")
        ref = got.data["product"]

    # Identity and profile come from the token only. A profile id of another account answers exactly like a missing one.
    profile = None
    if rt.principal is not None:
        pr = await rt.call("get_shopper_profile", {"profile_id": profile_id} if profile_id else {})
        if pr.ok:
            profile = pr.data["profile"]
        elif profile_id:
            raise ApiException(404, "PROFILE_NOT_FOUND", "Profile not found")
    elif profile_id:
        raise ApiException(401, "UNAUTHORIZED", "Sign in to use a profile")

    history = await _history(rt)
    # Purchase history belongs to the account holder: it never shapes a family member's recommendations, and it is not used when the
    # profile has personalisation switched off. What the account already bought is still never re-recommended.
    use_history = profile is None or (profile["kind"] == "OWNER" and profile["personalization_enabled"])
    ctx = RecoContext(
        scenario=scenario, reference=ref, reference_role=(ref.get("sport"), _role(ref, ref.get("sport"))) if ref else None, profile=profile,
        purchased_ids={str(i) for i in history.get("purchased_ids", set())},
        history_brands=set(history.get("brands", set())) if use_history else set(),
        history_sports=set(history.get("sports", {})) if use_history else set(),
        history_range=(history.get("min_price"), history.get("max_price")) if use_history else (None, None))
    prefs = ctx.prefs
    ref_price = effective_price(ref) if ref else None
    if ref and ref_price is None:
        raise ApiException(422, "NO_PRICE", "That product has no current price, so a price-based recommendation is not possible")

    bands = bands_for(scenario, ref_price, req.budget, policy, (prefs.get("price_min"), prefs.get("price_max")))
    if scenario is Scenario.PERSONALIZED and bands[0].source == "profile":
        bands.append(Band(None, None, "fallback", None, "Widened beyond the price range in your profile because too few items fit it"))
    cap = widest(bands)

    # ---- candidates
    evidence: dict[str, Any] | None = None
    message: str | None = None
    suggested: str | None = None
    age_ask = "junior" if ctx.child else None
    if scenario is Scenario.COMPLEMENTARY:
        pool = await _gather_complementary(rt, ref, cap.high, age_ask)
    elif scenario is Scenario.FREQUENTLY_BOUGHT_TOGETHER:
        co = await rt.call("get_co_purchases", {"product_id": ref_id, "limit": 10}) if rt.principal is not None else None
        pairs = co.data["pairs"] if co is not None and co.ok else []
        pairs = [p for p in pairs if p["orders"] >= policy.copurchase_min_orders]
        evidence = {"minimum_orders": policy.copurchase_min_orders, "pairs": len(pairs), "available": co is not None and co.ok}
        ctx.copurchase_orders = {p["product_id"]: p["orders"] for p in pairs}
        cards = await asyncio.gather(*(rt.call("get_product", {"product_id": p["product_id"]}) for p in pairs))
        pool = [c.data["product"] for c in cards if c.ok]
        if not pool:
            message = ("Sign in to see what other customers buy with this product." if rt.principal is None else
                       "There is not enough purchase history yet to show what is bought together with this product.")
            suggested = Scenario.COMPLEMENTARY.value
    elif scenario in (Scenario.SIMILAR, Scenario.UPGRADE):
        pool = await _gather_comparable(rt, ref, cap.low, cap.high, age_ask)
    else:
        pool = await _gather_personalized(rt, ctx, bands[0])
        if not pool or not (ctx.prefs or ctx.history_sports):
            pool = await _search(rt, limit=10)          # nothing to personalise from: the catalogue's own top matches, labelled as such
            ctx.profile = None
            message = "Add what you play to your profile (or place an order) to get recommendations that fit you. Here are top picks from the catalogue."

    seen: set[str] = set()
    candidates = []
    for c in pool:
        pid = c.get("product_id")
        if pid and pid not in seen:
            seen.add(pid)
            c["_role"] = _role(c, c.get("sport"))
            candidates.append(c)

    rejected: dict[str, int] = {}
    legal = []
    for c in candidates:
        why = hard_violation(c, ctx)
        if why:
            rejected[why] = rejected.get(why, 0) + 1
        else:
            legal.append(c)

    # ---- budget band: first band with enough real candidates; the last band is accepted even if thin
    applied, eligible, tried = bands[-1], [], []
    for i, band in enumerate(bands):
        in_band = [c for c in legal if band.contains(effective_price(c))]
        tried.append({"label": band.label, "source": band.source, "pct": band.pct, "candidates": len(in_band)})
        if len(in_band) >= policy.min_candidates or i == len(bands) - 1:
            applied, eligible = band, in_band
            break
    if scenario is Scenario.PERSONALIZED and applied.source == "none":
        applied = Band(None, None, "none", None, "")
    ranked = diversify(rank(eligible, ctx), limit, policy.max_per_group)
    items = [_item(c, scenario, ref, applied) for c in ranked]

    if not items and not message:
        if rejected.get("not_for_child_profile", 0) and rejected["not_for_child_profile"] >= len(candidates) / 2:
            who = profile["display_name"] if profile else "this profile"
            message = f"No products marked suitable for juniors match {who}'s preferences yet. Adult-only products are never shown on a child's profile."
        elif applied.source == "explicit":
            message = "Nothing in stock fits the budget you set. Try a higher budget."
        else:
            message = "No in-stock products fit this recommendation right now."
    latency_ms = round((time.perf_counter() - started) * 1000)
    meta = {"candidates_evaluated": len(candidates), "legal_candidates": len(legal), "rejected": rejected, "results": len(items), "latency_ms": latency_ms,
            "llm_tokens": 0, "tools_called": rt.ctx.calls}
    metrics.RECO_REQUESTS.labels(scenario.value, "results" if items else "empty", applied.source).inc()
    metrics.RECO_LATENCY.labels(scenario.value).observe(latency_ms / 1000)
    metrics.RECO_CANDIDATES.labels(scenario.value).observe(len(candidates))
    log.info("reco scenario=%s band=%s candidates=%d legal=%d results=%d fallback=%s latency_ms=%d llm_tokens=0", scenario.value, applied.source,
             len(candidates), len(legal), len(items), applied.source == "fallback", latency_ms)

    profile_view = None
    if profile:
        profile_view = {"profile_id": profile["profile_id"], "display_name": profile["display_name"], "kind": profile["kind"],
                        "personalization_applied": bool(prefs) and not prefs.get("empty", True), "personalization_enabled": profile["personalization_enabled"]}
    title = {Scenario.COMPLEMENTARY: "Goes well with this", Scenario.FREQUENTLY_BOUGHT_TOGETHER: "Frequently bought together",
             Scenario.SIMILAR: "Similar products", Scenario.UPGRADE: "Premium alternatives", Scenario.PERSONALIZED: "Picked for you"}[scenario]
    because = [applied.label] if applied.label else []
    if profile_view and profile_view["personalization_applied"]:
        because.append(f"Using the preferences in {profile['display_name'] if profile['kind'] == 'FAMILY' else 'your'} profile")
    return {
        "title": title, "personalized": bool(items) and (bool(profile_view and profile_view["personalization_applied"]) or (use_history and bool(history.get("cards")) and not message)), "because": because, "items": items,
        "bundle": None, "scenario": scenario.value, "personalization_note": None if use_history else "Purchase history is not used for this profile.",
        "reference": {"product_id": ref["product_id"], "name": ref["name"], "price": ref_price} if ref else None,
        "budget": {"explicit": req.budget, "complementary_ceiling_pct": policy.complementary_ceiling_pct if scenario is Scenario.COMPLEMENTARY else None,
                   "applied": {"low": applied.low, "high": applied.high, "source": applied.source, "pct": applied.pct, "label": applied.label},
                   "fallback_used": applied.source == "fallback", "tried": tried},
        "profile": profile_view, "evidence": evidence, "message": message, "suggested_scenario": suggested, "meta": meta,
    }
