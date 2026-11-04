"""Deterministic product ranking. No model is involved: same inputs, same order, always.

score = sum(weight_i * factor_i) / sum(weight_i)        over the factors that APPLY to this request

A factor applies only when the customer expressed that preference (or the data to evaluate it exists). Factors that do
not apply are left out and the remaining weights are renormalised, so an unmentioned preference never helps or hurts.

| factor        | weight | meaning                                                                                      |
|---------------|--------|----------------------------------------------------------------------------------------------|
| semantic      | 0.20   | retrieval fusion score (keyword + embedding) as a share of the best possible score (absolute)  |
| category      | 0.15   | 1 if the product category is the requested one                                                |
| sport         | 0.10   | 1 if the product sport is the requested one                                                   |
| age           | 0.10   | ageGroup attribute (junior/adult) equals the requested group: 1.0, otherwise 0.0 (unknown 0.5)                |
| skill         | 0.15   | skillLevel attribute: exact 1.0, all_levels 0.8, adjacent level 0.4, other 0.0, unknown 0.5   |
| budget        | 0.10   | 1 inside the budget (candidates outside it are excluded earlier unless nothing fits)          |
| weight        | 0.10   | weightClass attribute (light/medium/heavy) blended with the lightest..heaviest candidate scale |
| availability  | 0.05   | 1.0 comfortably in stock (>= 5), 0.6 low stock                                                |
| rating        | 0.05   | (average-3)/2 clipped to 0..1; only with >= 3 approved reviews, and it can never override a filter |

Ties break on lower price, then name, so the order is total and stable.
"""
from __future__ import annotations

from dataclasses import dataclass

from pydantic import BaseModel

from app.shop.catalog import ProductFacts

WEIGHTS: dict[str, float] = {
    "semantic": 0.20, "category": 0.15, "sport": 0.10, "skill": 0.15, "age": 0.10,
    "budget": 0.10, "weight": 0.10, "availability": 0.05, "rating": 0.05,
}
LOW_STOCK_THRESHOLD = 5
_SKILL_ORDER = ["beginner", "intermediate", "advanced"]
_WEIGHT_CLASS = {"light": 1.0, "medium": 0.5, "heavy": 0.0}


class Preferences(BaseModel):
    sport: str | None = None
    category: str | None = None
    skill_level: str | None = None          # beginner | intermediate | advanced
    max_price: float | None = None
    min_price: float | None = None
    weight_pref: str | None = None          # light | heavy
    age_group: str | None = None            # junior | adult


@dataclass(frozen=True)
class Ranked:
    product: ProductFacts
    score: float
    factors: dict[str, float]
    semantic: float


def _norm(s: str | None) -> str:
    return (s or "").strip().lower()


def skill_factor(wanted: str, attributes: dict[str, str]) -> float:
    have = _norm(attributes.get("skillLevel"))
    if not have:
        return 0.5
    if have == wanted:
        return 1.0
    if have == "all_levels":
        return 0.8
    if wanted in _SKILL_ORDER and have in _SKILL_ORDER and abs(_SKILL_ORDER.index(wanted) - _SKILL_ORDER.index(have)) == 1:
        return 0.4
    return 0.0


def weight_factor(pref: str, product: ProductFacts, lightest: float | None, heaviest: float | None) -> float | None:
    cls = _WEIGHT_CLASS.get(_norm(product.attributes.get("weightClass")))
    scale = None
    if product.weight_g is not None and lightest is not None and heaviest is not None and heaviest > lightest:
        scale = 1.0 - (product.weight_g - lightest) / (heaviest - lightest)  # 1 = lightest candidate
    if cls is None and scale is None:
        return None
    parts = [v for v in (cls, scale) if v is not None]
    light_score = sum(parts) / len(parts)
    return light_score if pref == "light" else 1.0 - light_score


def rank(candidates: list[tuple[ProductFacts, float]], prefs: Preferences, *, score_ceiling: float | None = None) -> list[Ranked]:
    """candidates: (live product facts, raw retrieval fusion score). Returns best first.

    `score_ceiling` is the best score retrieval can give (e.g. rank 1 in both lists). With it, the semantic factor is ABSOLUTE
    (score / ceiling), so near-identical retrieval scores stay near-identical and cannot outweigh an explicit preference. Without it
    (tests, unknown scale) the scores are min-max scaled across the candidates."""
    if not candidates:
        return []
    raw = [s for _, s in candidates]
    lo, hi = min(raw), max(raw)
    weights_g = [p.weight_g for p, _ in candidates if p.weight_g is not None]
    lightest, heaviest = (min(weights_g), max(weights_g)) if weights_g else (None, None)

    ranked: list[Ranked] = []
    for product, raw_score in candidates:
        semantic = min(1.0, raw_score / score_ceiling) if score_ceiling else (1.0 if hi == lo else (raw_score - lo) / (hi - lo))
        factors: dict[str, float] = {"semantic": semantic}
        if prefs.category:
            factors["category"] = 1.0 if _norm(prefs.category) == _norm(product.category) else 0.0
        if prefs.sport:
            factors["sport"] = 1.0 if _norm(prefs.sport) == _norm(product.sport) else 0.0
        if prefs.skill_level:
            factors["skill"] = skill_factor(_norm(prefs.skill_level), product.attributes)
        if prefs.age_group:
            have = _norm(product.attributes.get("ageGroup"))
            factors["age"] = 0.5 if not have else (1.0 if have == _norm(prefs.age_group) else 0.0)
        if (prefs.max_price is not None or prefs.min_price is not None) and product.price:
            price = product.price.selling_price
            inside = (prefs.max_price is None or price <= prefs.max_price) and (prefs.min_price is None or price >= prefs.min_price)
            factors["budget"] = 1.0 if inside else 0.0
        if prefs.weight_pref in ("light", "heavy"):
            wf = weight_factor(prefs.weight_pref, product, lightest, heaviest)
            if wf is not None:
                factors["weight"] = wf
        if product.stock is not None:
            factors["availability"] = 0.0 if not product.stock.in_stock else (
                1.0 if product.stock.available_quantity >= LOW_STOCK_THRESHOLD else 0.6)
        if product.rating and product.rating.count >= 3:
            factors["rating"] = max(0.0, min(1.0, (product.rating.average - 3.0) / 2.0))
        total_w = sum(WEIGHTS[k] for k in factors)
        score = sum(WEIGHTS[k] * v for k, v in factors.items()) / total_w
        ranked.append(Ranked(product, round(score, 6), {k: round(v, 4) for k, v in factors.items()}, round(semantic, 6)))

    ranked.sort(key=lambda r: (-r.score, r.product.price.selling_price if r.product.price else float("inf"), r.product.name))
    return ranked
