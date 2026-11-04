"""Pilot planning for a NEW product (a Phase 5 opportunity) that has no sales history. Pure functions.

A new product cannot be forecast, only PILOTED: the estimate comes from COMPARABLE products (same sport and category, nearest price) sold in the same stores, always as a RANGE
and always LOW confidence, with its assumptions listed. This module never publishes a product, buys stock or changes a price: it proposes where to test, how much stock and for
how long, then judges the actual pilot sales against the estimate.

  per-store weekly estimate  = median / 25th / 75th percentile over the comparables of their average weekly units in that store (recent 13 weeks)
  pilot stock per store      = ceil(75th percentile x duration weeks): enough that a good result is not cut short by running out
  candidate stores           = the best-selling stores for the comparable category, at least one per region first, only stores with enough history to trust the comparison
  reassessment               needs at least MIN_OBSERVATIONS store-weeks with the product available most of the week, otherwise the answer is CONTINUE_TESTING
"""
from __future__ import annotations

import math
from typing import Any, Sequence

import numpy as np

MIN_COMPARABLES = 3
MIN_STORE_HISTORY_WEEKS = 26
MIN_OBSERVATIONS = 8
MIN_AVAILABILITY = 0.8
MAX_STORES = 6


def pick_comparables(candidate: dict[str, Any], catalogue: Sequence[dict[str, Any]], limit: int = 5) -> list[dict[str, Any]]:
    """Same sport and category as the candidate; nearest selling price first when the candidate has one, otherwise the highest-volume ones. Never the product itself."""
    sport, cat = (candidate.get("sport") or "").lower(), (candidate.get("category") or "").lower()
    pool = [c for c in catalogue if (c.get("sport") or "").lower() == sport and (c.get("category") or "").lower() == cat and c.get("product_id") != candidate.get("product_id")]
    price = candidate.get("selling_price")
    if price:
        pool.sort(key=lambda c: abs(float(c.get("price") or 0) - float(price)))
    else:
        pool.sort(key=lambda c: -float(c.get("weekly_units") or 0))
    return pool[:limit]


def estimate(comparables: Sequence[dict[str, Any]], store_rates: dict[str, dict[str, float]], store_history_weeks: dict[str, int], store_region: dict[str, str], duration_weeks: int,
             max_stores: int = MAX_STORES) -> dict[str, Any]:
    """`store_rates[product_id][location_id]` = average weekly units over the recent weeks; returns the proposal or an explanation of why there is none."""
    assumptions = ["Comparable products are the same sport and category (nearest price when the price is known).",
                   "The new product sells like the comparables in the same store: this is an assumption, not a measurement.",
                   "No cannibalisation of the comparables and no launch effect (promotion, novelty) is modelled.",
                   "The range is the 25th to 75th percentile over the comparables, not a statistical interval."]
    if len(comparables) < MIN_COMPARABLES:
        return {"status": "INSUFFICIENT_COMPARABLES", "confidence": "NONE", "reason": f"Only {len(comparables)} comparable product(s) found (need {MIN_COMPARABLES}): no estimate is made.",
                "comparables": [c["name"] for c in comparables], "stores": [], "assumptions": assumptions, "duration_weeks": duration_weeks}
    per_store: dict[str, list[float]] = {}
    for c in comparables:
        for loc, rate in store_rates.get(c["product_id"], {}).items():
            if store_history_weeks.get(loc, 0) >= MIN_STORE_HISTORY_WEEKS:
                per_store.setdefault(loc, []).append(float(rate))
    rows = []
    for loc, rates in per_store.items():
        if len(rates) < MIN_COMPARABLES:
            continue
        lo, mid, hi = (float(np.percentile(rates, q)) for q in (25, 50, 75))
        rows.append({"location_id": loc, "region": store_region.get(loc, "?"), "weekly_lo": round(lo, 2), "weekly_mid": round(mid, 2), "weekly_hi": round(hi, 2), "comparables": len(rates)})
    if not rows:
        return {"status": "INSUFFICIENT_STORE_HISTORY", "confidence": "NONE", "reason": "No store has enough history for the comparable products: no estimate is made.",
                "comparables": [c["name"] for c in comparables], "stores": [], "assumptions": assumptions, "duration_weeks": duration_weeks}
    rows.sort(key=lambda r: -r["weekly_mid"])
    chosen, seen = [], set()
    for r in rows:                                               # the best store of each region first, so the pilot is not concentrated
        if r["region"] not in seen and len(chosen) < max_stores:
            chosen.append(r)
            seen.add(r["region"])
    for r in rows:
        if r not in chosen and len(chosen) < max_stores:
            chosen.append(r)
    stores = []
    for r in sorted(chosen, key=lambda r: -r["weekly_mid"]):
        stores.append({**r, "pilot_qty": int(math.ceil(r["weekly_hi"] * duration_weeks)), "expected_units_lo": round(r["weekly_lo"] * duration_weeks, 1),
                       "expected_units_mid": round(r["weekly_mid"] * duration_weeks, 1), "expected_units_hi": round(r["weekly_hi"] * duration_weeks, 1)})
    return {"status": "ESTIMATED", "confidence": "LOW", "reason": "Estimated from comparable products only; the product has no sales history.", "comparables": [c["name"] for c in comparables],
            "stores": stores, "assumptions": assumptions, "duration_weeks": duration_weeks, "total_pilot_qty": sum(s["pilot_qty"] for s in stores)}


def reassess(est: dict[str, Any], observations: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Judge actual pilot sales against the estimate. An observation: {location_id, units, available_days (0..7)}. Weeks with the product available less than most of the week are excluded:
    a stock-out is not evidence of low demand."""
    by_store = {s["location_id"]: s for s in est.get("stores", [])}
    usable = [o for o in observations if o["location_id"] in by_store and o.get("available_days", 7) / 7.0 >= MIN_AVAILABILITY]
    excluded = len(observations) - len(usable)
    if len(usable) < MIN_OBSERVATIONS:
        return {"decision": "CONTINUE_TESTING", "sufficient": False, "usable_observations": len(usable), "excluded_for_availability": excluded,
                "reason": f"{len(usable)} usable store-week(s) so far (need {MIN_OBSERVATIONS}): too few to judge. Keep testing."}
    actual = float(np.mean([o["units"] for o in usable]))
    exp_lo = float(np.mean([by_store[o["location_id"]]["weekly_lo"] for o in usable]))
    exp_mid = float(np.mean([by_store[o["location_id"]]["weekly_mid"] for o in usable]))
    exp_hi = float(np.mean([by_store[o["location_id"]]["weekly_hi"] for o in usable]))
    if actual >= exp_hi:
        decision, why = "EXPAND", f"Average {actual:.1f} units per store-week is at or above the top of the estimated range ({exp_hi:.1f})."
    elif actual < exp_lo:
        decision, why = "DISCONTINUE", f"Average {actual:.1f} units per store-week is below the bottom of the estimated range ({exp_lo:.1f}) with enough observations."
    else:
        decision, why = "CONTINUE_TESTING", f"Average {actual:.1f} units per store-week is inside the estimated range ({exp_lo:.1f} to {exp_hi:.1f}): not clearly better or worse than the comparables."
    return {"decision": decision, "sufficient": True, "usable_observations": len(usable), "excluded_for_availability": excluded, "actual_weekly_avg": round(actual, 2),
            "expected_weekly": {"lo": round(exp_lo, 2), "mid": round(exp_mid, 2), "hi": round(exp_hi, 2)}, "reason": why,
            "note": "Margin is not judged here: it needs a real supplier quotation and a saved landed-cost calculation from Phase 5."}
