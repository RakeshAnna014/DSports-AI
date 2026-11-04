"""Event / tournament impact: an EXPLAINABLE model estimated from history, not a hard-coded "tournament = +X%".

1. MEASURE. For every PAST event and every product of the event's sport, uplift = actual units in the event window / the baseline the
   forecasting model expects for those days (event days were excluded from the baseline's training data). The ratio is taken over
   days with usable ground truth only (not stock-censored, not missing). Per (event, category) the median across products is the
   uplift and the 10th-90th percentile across products is its spread.
2. TRANSFER. An UPCOMING event is estimated from past events of the same sport (preferring the same event type), per category,
   after normalising each past uplift by the event's popularity (an input: 1.0 = typical) with exponent 0.6.
3. FALLBACKS, labelled in `basis`: a category never measured at a past event of that sport (too few products) -> the sport-wide average
   (HISTORICAL_SPORT_LEVEL); no history for that sport -> other sports' events of the same type (HISTORICAL_OTHER_SPORT); nothing at all
   -> a POC ASSUMPTION (1.3, range 1.0-1.6) that is clearly flagged and never presented as measured.

The effect is applied to the BASELINE FORECAST only inside the event's demand window; outside it nothing changes.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np

POPULARITY_EXPONENT = 0.6
MIN_EVENT_DAYS = 5
ASSUMED = (1.3, 1.0, 1.6)     # POC assumption used ONLY when no history exists


@dataclass
class Evidence:
    event_id: str
    sport: str
    category: str
    uplift: float
    lo: float
    hi: float
    n_products: int


def measure_event(event: dict, days: list[date], per_product: list[dict]) -> list[Evidence]:
    """per_product items: {category, raw, fitted, unusable (all arrays over `days`), sport}."""
    idx = np.array([event["start_date"] <= d <= event["end_date"] for d in days])
    if not idx.any():
        return []
    by_cat: dict[str, list[float]] = {}
    for p in per_product:
        if p["sport"] != event["sport"]:
            continue
        ok = idx & ~p["unusable"] & ~np.isnan(p["raw"])
        if ok.sum() < MIN_EVENT_DAYS:
            continue
        expected = float(p["fitted"][ok].sum())
        if expected <= 0.5:
            continue
        by_cat.setdefault(p["category"], []).append(float(p["raw"][ok].sum()) / expected)
    out = []
    for cat, vals in by_cat.items():
        if len(vals) < 2:
            continue
        out.append(Evidence(event["id"], event["sport"], cat, float(np.median(vals)), float(np.quantile(vals, 0.10)),
                            float(np.quantile(vals, 0.90)), len(vals)))
    return out


def _normalise(value: float, popularity: float) -> float:
    return 1.0 + (value - 1.0) / (popularity ** POPULARITY_EXPONENT)


def estimate_upcoming(event: dict, past: list[tuple[dict, list[Evidence]]], categories: list[str]) -> dict[str, dict]:
    """category -> {uplift, lo, hi, n_events, n_products, basis, evidence: [event ids]} for one upcoming event."""
    if not event["popularity"] > 0:
        raise ValueError("event popularity must be positive")
    scale = event["popularity"] ** POPULARITY_EXPONENT

    def pooled(rows: list[tuple[dict, Evidence]]) -> tuple[float, float, float, int, int] | None:
        if not rows:
            return None
        u = [_normalise(e.uplift, ev["popularity"]) for ev, e in rows]
        lo = [_normalise(e.lo, ev["popularity"]) for ev, e in rows]
        hi = [_normalise(e.hi, ev["popularity"]) for ev, e in rows]
        return (1.0 + (float(np.mean(u)) - 1.0) * scale, 1.0 + (float(np.mean(lo)) - 1.0) * scale,
                1.0 + (float(np.mean(hi)) - 1.0) * scale, len({ev["id"] for ev, _ in rows}), int(sum(e.n_products for _, e in rows)))

    same_sport = [(ev, e) for ev, evs in past for e in evs if ev["sport"] == event["sport"]]
    same_type = [(ev, e) for ev, e in same_sport if ev["event_type"] == event["event_type"]]
    other_sport = [(ev, e) for ev, evs in past for e in evs if ev["sport"] != event["sport"] and ev["event_type"] == event["event_type"]]
    out: dict[str, dict] = {}
    for cat in categories:
        for basis, pool in (("HISTORICAL", [(ev, e) for ev, e in (same_type or same_sport) if e.category == cat]),
                            ("HISTORICAL_OTHER_SPORT", [(ev, e) for ev, e in other_sport if e.category == cat])):
            r = pooled(pool)
            if r:
                out[cat] = {"uplift": round(r[0], 3), "lo": round(min(r[1], r[0]), 3), "hi": round(max(r[2], r[0]), 3), "n_events": r[3],
                            "n_products": r[4], "basis": basis, "same_event_type": bool(same_type) or basis != "HISTORICAL"}
                break
        else:
            # category never observed at a past event of this sport: use how the sport's other categories behaved, else the assumption
            r = pooled(same_type or same_sport)
            if r and event["event_type"] != "marathon":
                out[cat] = {"uplift": round(r[0], 3), "lo": round(min(r[1], r[0]), 3), "hi": round(max(r[2], r[0]), 3), "n_events": r[3],
                            "n_products": r[4], "basis": "HISTORICAL_SPORT_LEVEL", "note": "category not measured at past events: sport-wide average uplift"}
            else:
                out[cat] = {"uplift": ASSUMED[0], "lo": ASSUMED[1], "hi": ASSUMED[2], "n_events": 0, "n_products": 0, "basis": "POC_ASSUMPTION"}
    return out


def apply_to_forecast(daily: np.ndarray, as_of: date, event: dict, uplift: float) -> np.ndarray:
    """Baseline daily forecast (day 1 = as_of) multiplied by the uplift inside the event window only."""
    out = np.asarray(daily, dtype=float).copy()
    start = (event["start_date"] - as_of).days
    end = (event["end_date"] - as_of).days
    lo, hi = max(0, start), min(len(out) - 1, end)
    if hi >= lo:
        out[lo:hi + 1] *= uplift
    return out
