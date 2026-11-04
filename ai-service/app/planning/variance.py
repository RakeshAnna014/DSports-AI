"""Forecast, target and actual compared: the formulas, and an evidence-first reading of a big difference. Pure functions.

  forecast variance (units)   = actual - approved forecast        (negative: sold less than forecast)
  target variance (units)     = actual - target
  forecast variance %         = (actual - forecast) / forecast x 100   (None when the forecast is 0)
  target attainment %         = actual / target x 100                 (None when the target is 0 or missing: never a division error, never 0%)
  forecast error (bias)       = forecast - actual                     (positive: over-forecast), the sign used by every accuracy report

The four values are DIFFERENT THINGS and are never merged: model forecast, manager forecast, approved forecast, target, actual. `approved forecast` is the forecast the plan was
approved with (the manager's number if there was one, otherwise the model's); a plan that is not approved has none, and variance is then reported against the model forecast and says so.

INVESTIGATION separates FACTS (taken from stored records) from HYPOTHESES (possible reasons, labelled as such). It never says that something CAUSED a difference.
"""
from __future__ import annotations

from typing import Any


def _num(v: Any) -> float | None:
    return None if v is None else float(v)


def compare(actual: float | None, forecast: float | None, target: float | None) -> dict[str, Any]:
    a, f, t = _num(actual), _num(forecast), _num(target)
    out: dict[str, Any] = {"actual": a, "forecast": f, "target": t, "forecast_variance": None, "forecast_variance_pct": None, "target_variance": None, "target_attainment_pct": None}
    if a is not None and f is not None:
        out["forecast_variance"] = round(a - f, 3)
        out["forecast_variance_pct"] = None if f == 0 else round((a - f) / f * 100, 1)
    if a is not None and t is not None:
        out["target_variance"] = round(a - t, 3)
        out["target_attainment_pct"] = None if t <= 0 else round(a / t * 100, 1)
    return out


def is_significant(variance: float | None, base: float | None, pct_threshold: float, min_units: float) -> bool:
    """A difference is worth a look when it is large in percent AND in units (a 50% miss on 2 units is noise)."""
    if variance is None or base is None:
        return False
    if abs(variance) < min_units:
        return False
    if base == 0:
        return True
    return abs(variance) / base * 100 >= pct_threshold


def rollup(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Sum rows of {actual, forecast, target}; a total is only a total over the rows that have the value, and says how many did (so a missing target is never counted as zero)."""
    def total(key: str) -> tuple[float | None, int]:
        vals = [r[key] for r in rows if r.get(key) is not None]
        return (round(sum(vals), 3) if vals else None), len(vals)
    a, na = total("actual")
    f, nf = total("forecast")
    t, nt = total("target")
    out = compare(a, f, t)
    both_f = [r for r in rows if r.get("actual") is not None and r.get("forecast") is not None]
    both_t = [r for r in rows if r.get("actual") is not None and r.get("target") is not None]
    if both_f:
        out["forecast_variance"] = round(sum(r["actual"] - r["forecast"] for r in both_f), 3)
        base = sum(r["forecast"] for r in both_f)
        out["forecast_variance_pct"] = None if base == 0 else round(out["forecast_variance"] / base * 100, 1)
    if both_t:
        out["target_variance"] = round(sum(r["actual"] - r["target"] for r in both_t), 3)
        base_t = sum(r["target"] for r in both_t)
        out["target_attainment_pct"] = None if base_t <= 0 else round(sum(r["actual"] for r in both_t) / base_t * 100, 1)
    out["coverage"] = {"rows": len(rows), "with_actual": na, "with_forecast": nf, "with_target": nt}
    return out


def investigate(*, actual: float | None, forecast: float | None, target: float | None, week_closed: bool, stockout_days: int | None, returned_units: int | None, cancelled_units: int | None,
                gross_units: int | None, history_weeks: int | None, confidence: str | None, recent_bias_pct: float | None, adjustments: list[dict[str, Any]],
                events: list[str], pct_threshold: float, min_units: float) -> dict[str, Any]:
    """Facts from stored records, then hypotheses. Nothing here is a cause."""
    cmp = compare(actual, forecast, target)
    facts: list[str] = []
    hypotheses: list[str] = []
    if not week_closed:
        return {"comparison": cmp, "flagged": False, "facts": ["The week is not closed: its actuals are incomplete, so no conclusion is drawn."], "hypotheses": [], "limits": ["Open week."]}
    flagged = is_significant(cmp["forecast_variance"], cmp["forecast"], pct_threshold, min_units) or is_significant(cmp["target_variance"], cmp["target"], pct_threshold, min_units)
    if actual is None:
        return {"comparison": cmp, "flagged": False, "facts": ["There is no actual for this week (missing data, not zero sales)."], "hypotheses": [], "limits": ["No actual."]}
    if cmp["forecast_variance"] is not None:
        facts.append(f"Actual {cmp['actual']:.0f} against forecast {cmp['forecast']:.0f}: {cmp['forecast_variance']:+.0f} units" + (f" ({cmp['forecast_variance_pct']:+.0f}%)." if cmp["forecast_variance_pct"] is not None else "."))
    if cmp["target_variance"] is not None:
        facts.append(f"Actual {cmp['actual']:.0f} against target {cmp['target']:.0f}: {cmp['target_variance']:+.0f} units" + (f" ({cmp['target_attainment_pct']:.0f}% attainment)." if cmp["target_attainment_pct"] is not None else "."))
    if stockout_days:
        facts.append(f"The source records {stockout_days} stock-out day(s) in the week: sales were limited by availability on those days.")
        if (cmp["forecast_variance"] or 0) < 0:
            hypotheses.append("Lower sales than forecast may reflect unavailable stock rather than lower demand.")
    if cancelled_units:
        facts.append(f"{cancelled_units} unit(s) were ordered and cancelled.")
    if returned_units:
        facts.append(f"{returned_units} unit(s) were returned and are subtracted from net sales.")
        if gross_units and (cmp["forecast_variance"] or 0) < 0 and gross_units >= (forecast or 0):
            hypotheses.append("Gross sales reached the forecast; returns or cancellations account for the shortfall in net units.")
    for adj in adjustments:
        facts.append(f"An approved correction of {adj['delta_units']:+d} unit(s) ({adj['reason_code']}) is included in the actual.")
    if history_weeks is not None and history_weeks < 26:
        facts.append(f"The forecast rests on only {history_weeks} week(s) of history.")
        hypotheses.append("A forecast from short history is unreliable; part of the difference may be forecast error rather than anything that happened.")
    if confidence == "LOW":
        facts.append("The forecast was low confidence when it was made.")
    if recent_bias_pct is not None and abs(recent_bias_pct) >= 15:
        facts.append(f"This series has been {'over' if recent_bias_pct > 0 else 'under'}-forecast by {abs(recent_bias_pct):.0f}% on average over recent weeks.")
        hypotheses.append("A persistent bias points at the forecast method for this series rather than a one-off event.")
    for e in events:
        facts.append(f"The reference calendar lists: {e}.")
        hypotheses.append("An event in the calendar may have changed demand, but the data does not show that it did.")
    explained = len(facts) > sum(1 for k in ("forecast_variance", "target_variance") if cmp[k] is not None)       # anything beyond the comparison lines themselves
    if not explained and flagged:
        hypotheses.append("The records do not explain the difference. Unexpected demand, a price or promotion change, or a data issue are possible; none is evidenced here.")
    return {"comparison": cmp, "flagged": bool(flagged), "facts": facts, "hypotheses": hypotheses,
            "limits": ["Price changes and promotions are not part of the stored weekly data, so they cannot be confirmed or excluded.",
                       "A hypothesis is a possible reason, not a finding: sales rising during a promotion does not show the promotion caused it."]}
