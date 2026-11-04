"""Deterministic budget and ranking policy for recommendations.

Pure functions only: no I/O, no clock, no model. Every number a customer sees (a price band, a ceiling, a score) is computed here from
data the Java backend returned, so it can be unit-tested and explained. The LLM never sets or relaxes any of it.

Pipeline (the engine feeds this module): scenario -> reference -> candidates -> HARD constraints -> budget band -> scoring -> diversity.

Hard constraints (never relaxed by any budget fallback): in stock, a known positive price, not the reference product, not already
bought, sport compatibility, the scenario's own compatibility rule, and the age safety rule for a child's profile.
"""
from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any, Iterable


class Scenario(str, enum.Enum):
    COMPLEMENTARY = "COMPLEMENTARY"                          # something that goes with the reference (a grip for a bat)
    FREQUENTLY_BOUGHT_TOGETHER = "FREQUENTLY_BOUGHT_TOGETHER"  # only with real co-purchase evidence
    SIMILAR = "SIMILAR"                                      # a comparable alternative at a comparable price
    UPGRADE = "UPGRADE"                                      # a premium alternative, only when the customer asks for it
    PERSONALIZED = "PERSONALIZED"                            # discovery from the active profile and purchase history


@dataclass(frozen=True)
class BudgetPolicy:
    complementary_ceiling_pct: float = 0.20
    fallback_pcts: tuple[float, ...] = (0.30, 0.40)
    similar_tolerance_pct: float = 0.25
    upgrade_min_multiple: float = 1.15
    upgrade_max_multiple: float = 2.0
    min_candidates: int = 2
    max_per_group: int = 2
    copurchase_min_orders: int = 2

    @classmethod
    def from_settings(cls, s: Any) -> "BudgetPolicy":
        return cls(complementary_ceiling_pct=s.reco_complementary_ceiling_pct, fallback_pcts=tuple(s.reco_fallback_pcts),
                   similar_tolerance_pct=s.reco_similar_tolerance_pct, upgrade_min_multiple=s.reco_upgrade_min_multiple,
                   upgrade_max_multiple=s.reco_upgrade_max_multiple, min_candidates=s.reco_min_candidates, max_per_group=s.reco_max_per_group,
                   copurchase_min_orders=s.reco_copurchase_min_orders)


@dataclass(frozen=True)
class Band:
    """A price range candidates must fall into. `source` says where it came from, so the UI can label it truthfully."""
    low: float | None
    high: float | None
    source: str          # explicit | policy | fallback | profile | none
    pct: float | None = None
    label: str = ""

    def contains(self, price: float) -> bool:
        return (self.low is None or price >= self.low) and (self.high is None or price <= self.high)


def effective_price(card: dict[str, Any]) -> float | None:
    """The price the customer would pay today (the live selling price from DSports), or None when unknown."""
    p = card.get("price")
    return float(p) if isinstance(p, (int, float)) and p > 0 else None


def _inr(v: float) -> str:
    return f"₹{v:,.0f}"


# ------------------------------------------------------------------------------------------------ budget bands

def bands_for(scenario: Scenario, reference_price: float | None, explicit_budget: float | None, policy: BudgetPolicy,
              profile_range: tuple[float | None, float | None] = (None, None)) -> list[Band]:
    """The ordered price bands to try. The first with enough real candidates wins. An explicit customer budget always takes
    precedence and is never widened: what the customer typed is the ceiling."""
    if explicit_budget is not None and explicit_budget > 0:
        low = reference_price * policy.upgrade_min_multiple if scenario is Scenario.UPGRADE and reference_price else None
        return [Band(low, float(explicit_budget), "explicit", None, f"Within your budget of {_inr(explicit_budget)}")]
    if scenario is Scenario.COMPLEMENTARY and reference_price:
        out = [Band(None, round(reference_price * policy.complementary_ceiling_pct, 2), "policy", policy.complementary_ceiling_pct,
                    f"Up to {policy.complementary_ceiling_pct:.0%} of the price of the product you picked ({_inr(reference_price * policy.complementary_ceiling_pct)})")]
        out += [Band(None, round(reference_price * pct, 2), "fallback", pct,
                     f"Widened to up to {pct:.0%} of the product's price ({_inr(reference_price * pct)}) because too few items fit {policy.complementary_ceiling_pct:.0%}")
                for pct in policy.fallback_pcts if pct > policy.complementary_ceiling_pct]
        return out
    if scenario is Scenario.SIMILAR and reference_price:
        lo, hi = reference_price * (1 - policy.similar_tolerance_pct), reference_price * (1 + policy.similar_tolerance_pct)
        return [Band(round(lo, 2), round(hi, 2), "policy", policy.similar_tolerance_pct,
                     f"Within {policy.similar_tolerance_pct:.0%} of the price of the product you picked ({_inr(lo)}–{_inr(hi)})")]
    if scenario is Scenario.UPGRADE and reference_price:
        lo, hi = reference_price * policy.upgrade_min_multiple, reference_price * policy.upgrade_max_multiple
        return [Band(round(lo, 2), round(hi, 2), "policy", None, f"Premium options priced {_inr(lo)}–{_inr(hi)}")]
    if scenario is Scenario.PERSONALIZED and (profile_range[0] is not None or profile_range[1] is not None):
        lo, hi = profile_range
        text = (f"{_inr(lo)}–{_inr(hi)}" if lo is not None and hi is not None else f"up to {_inr(hi)}" if hi is not None else f"from {_inr(lo)}")
        return [Band(lo, hi, "profile", None, f"Within the price range in your profile ({text})")]
    return [Band(None, None, "none", None, "")]


def widest(bands: Iterable[Band]) -> Band:
    """The band with the highest ceiling (used to size the candidate search once)."""
    bs = list(bands)
    return max(bs, key=lambda b: float("inf") if b.high is None else b.high)


# ------------------------------------------------------------------------------------------------ context and hard constraints

@dataclass
class RecoContext:
    scenario: Scenario
    reference: dict[str, Any] | None = None
    reference_role: tuple[str | None, str | None] | None = None      # (sport, kit role) of the reference
    profile: dict[str, Any] | None = None                            # as returned by get_shopper_profile (already whitelisted)
    purchased_ids: set[str] = field(default_factory=set)
    history_brands: set[str] = field(default_factory=set)
    history_sports: set[str] = field(default_factory=set)
    history_range: tuple[float | None, float | None] = (None, None)
    copurchase_orders: dict[str, int] = field(default_factory=dict)
    cross_sport_ids: set[str] = field(default_factory=set)           # candidates allowed across sports by an explicit relation table

    @property
    def prefs(self) -> dict[str, Any]:
        """Preferences are used only when the profile exists, is not empty and personalisation is switched on for it."""
        if not self.profile or not self.profile.get("personalization_enabled", True):
            return {}
        return self.profile.get("preferences") or {}

    @property
    def child(self) -> bool:
        return (self.profile or {}).get("age_band") in ("CHILD_UNDER_8", "CHILD_8_12")


def _norm(v: Any) -> str:
    return str(v).strip().lower() if v is not None else ""


def hard_violation(c: dict[str, Any], ctx: RecoContext) -> str | None:
    """Why a candidate may never be shown, or None. No budget fallback can override any of these."""
    ref = ctx.reference
    if c.get("in_stock") is not True:
        return "out_of_stock"
    if effective_price(c) is None:
        return "no_price"
    if ref and c.get("product_id") == ref.get("product_id"):
        return "is_reference"
    if c.get("product_id") in ctx.purchased_ids:
        return "already_bought"
    if ctx.child and _norm((c.get("attributes") or {}).get("ageGroup")) == "adult":
        return "not_for_child_profile"
    if ref and ctx.scenario is not Scenario.FREQUENTLY_BOUGHT_TOGETHER:
        if c.get("sport") != ref.get("sport") and c.get("product_id") not in ctx.cross_sport_ids:
            return "incompatible_sport"
        role = c.get("_role")
        ref_role = ctx.reference_role[1] if ctx.reference_role else None
        if ctx.scenario is Scenario.COMPLEMENTARY:
            if role is None or role == ref_role:
                return "not_complementary"
        elif ctx.scenario in (Scenario.SIMILAR, Scenario.UPGRADE):
            if c.get("category") != ref.get("category") or (ref_role is not None and role != ref_role):
                return "not_comparable"
            if ctx.scenario is Scenario.UPGRADE and effective_price(c) <= (effective_price(ref) or 0):
                return "not_an_upgrade"
    return None


# ------------------------------------------------------------------------------------------------ scoring

WEIGHTS = {
    "profile_sport": 3.0, "profile_brand": 2.0, "profile_category": 1.0, "usage_level": 2.0, "profile_price": 1.0, "history_brand": 1.0,
    "history_price": 1.0, "reference_brand": 0.5, "co_purchase_per_order": 0.5, "co_purchase_cap": 3.0, "rating": 1.0, "discount": 1.0,
    "closeness": 2.0, "premium_skill": 1.5, "junior_for_adult": -1.5,
}
LEVEL_ORDER = ["beginner", "intermediate", "advanced", "professional"]


def score(c: dict[str, Any], ctx: RecoContext) -> tuple[float, list[str]]:
    """Weighted, deterministic score and the list of FACTUAL reasons behind it. Ties are broken by price then name by the caller."""
    s, why = 0.0, []
    prefs, ref = ctx.prefs, ctx.reference
    price = effective_price(c) or 0.0
    attrs = c.get("attributes") or {}

    if prefs:
        if c.get("sport") and _norm(c["sport"]) in {_norm(x) for x in prefs.get("sports", [])}:
            s += WEIGHTS["profile_sport"]; why.append(f"Matches {c['sport']}, a sport in your profile")
        if c.get("brand") and _norm(c["brand"]) in {_norm(x) for x in prefs.get("brands", [])}:
            s += WEIGHTS["profile_brand"]; why.append(f"{c['brand']} is a brand you follow")
        if c.get("category") and _norm(c["category"]) in {_norm(x) for x in prefs.get("categories", [])}:
            s += WEIGHTS["profile_category"]; why.append(f"{c['category']} is a category you follow")
        level, have = _norm(prefs.get("usage_level")), _norm(attrs.get("skillLevel"))
        if level and have and (have == level or have == "all_levels"):
            s += WEIGHTS["usage_level"]; why.append(f"Suited to {level} level")
        lo, hi = prefs.get("price_min"), prefs.get("price_max")
        if (lo is not None or hi is not None) and (lo is None or price >= lo) and (hi is None or price <= hi):
            s += WEIGHTS["profile_price"]; why.append("Inside the price range in your profile")
    if c.get("brand") and c["brand"] in ctx.history_brands:
        s += WEIGHTS["history_brand"]; why.append(f"You have bought {c['brand']} before")
    lo, hi = ctx.history_range
    if lo is not None and hi is not None and lo * 0.6 <= price <= hi * 1.5:
        s += WEIGHTS["history_price"]; why.append(f"Within your usual price range ({_inr(lo)}–{_inr(hi)})")

    if ref:
        if c.get("brand") and c.get("brand") == ref.get("brand"):
            s += WEIGHTS["reference_brand"]; why.append(f"Same brand as {ref['name']}")
        ref_price = effective_price(ref)
        if ctx.scenario is Scenario.SIMILAR and ref_price:
            closeness = max(0.0, 1 - abs(price - ref_price) / ref_price)
            s += WEIGHTS["closeness"] * closeness
            why.append(f"{abs(price - ref_price) / ref_price:.0%} {'cheaper' if price < ref_price else 'dearer' if price > ref_price else 'the same price'} than {ref['name']}"
                       if price != ref_price else f"Same price as {ref['name']}")
        if ctx.scenario is Scenario.UPGRADE:
            ref_level, level = _norm((ref.get("attributes") or {}).get("skillLevel")), _norm(attrs.get("skillLevel"))
            if ref_level in LEVEL_ORDER and level in LEVEL_ORDER and LEVEL_ORDER.index(level) > LEVEL_ORDER.index(ref_level):
                s += WEIGHTS["premium_skill"]; why.append(f"Built for {level} players (yours is {ref_level})")
            if ref_price:
                why.append(f"{_inr(price - ref_price)} more than {ref['name']}")

    n = ctx.copurchase_orders.get(c.get("product_id", ""), 0)
    if n:
        s += min(WEIGHTS["co_purchase_cap"], n * WEIGHTS["co_purchase_per_order"])
        why.append(f"Bought together with {ref['name'] if ref else 'this product'} in {n} orders")

    rating = c.get("rating") or {}
    if rating.get("count") and rating.get("average"):
        s += WEIGHTS["rating"] * float(rating["average"]) / 5.0
        if rating["average"] >= 4.0:
            why.append(f"Rated {rating['average']:.1f}/5 by {rating['count']} customers")
    mrp = c.get("mrp")
    if isinstance(mrp, (int, float)) and mrp > price:
        frac = (mrp - price) / mrp
        s += WEIGHTS["discount"] * frac
        why.append(f"{frac:.0%} off the list price (you save {_inr(mrp - price)})")
    if _norm(attrs.get("ageGroup")) == "junior" and (ctx.profile or {}).get("age_band") == "ADULT":
        s += WEIGHTS["junior_for_adult"]
    return round(s, 4), why


def diversify(ranked: list[dict[str, Any]], limit: int, max_per_group: int) -> list[dict[str, Any]]:
    """Keeps the order but allows at most `max_per_group` items of one category and of one brand, so a result is not five of the same thing."""
    out, cats, brands = [], {}, {}
    for c in ranked:
        cat, brand = c.get("category"), c.get("brand")
        if cats.get(cat, 0) >= max_per_group or (brand and brands.get(brand, 0) >= max_per_group):
            continue
        cats[cat] = cats.get(cat, 0) + 1
        if brand:
            brands[brand] = brands.get(brand, 0) + 1
        out.append(c)
        if len(out) >= limit:
            break
    return out


def rank(candidates: list[dict[str, Any]], ctx: RecoContext) -> list[dict[str, Any]]:
    """Scored candidates, best first. Deterministic: equal scores fall back to the lower price, then the name, then the id."""
    scored = []
    for c in candidates:
        sc, why = score(c, ctx)
        scored.append({**c, "score": sc, "_why": why})
    scored.sort(key=lambda c: (-c["score"], effective_price(c) or 0.0, str(c.get("name", "")), str(c.get("product_id", ""))))
    return scored
