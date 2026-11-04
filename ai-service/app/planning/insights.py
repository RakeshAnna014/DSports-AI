"""AI-assisted planning analysis: the FINDINGS are computed here, by deterministic code, from persisted records. Nothing in this module calls an LLM.

A finding separates what was observed (`facts`, with numbers taken from stored records) from what might explain it (`hypotheses`, labelled as such), says which records it rests on (`evidence`,
each a drill-down target in the application), where the data came from (`provenance`), how strong the evidence is, what it assumes, and what a person could do next. A forecast or target change is
only ever PROPOSED (`proposal`): saving it goes through the ordinary, audited, version-checked plan edit and the normal approval workflow.

The optional LLM step (see `PlanningService.analyze`) only re-words these findings into a short summary and its text is kept only if every number in it appears in the findings.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

SEVERITY_ORDER = {"HIGH": 0, "MEDIUM": 1, "LOW": 2, "INFO": 3}


@dataclass(frozen=True)
class Thresholds:
    variance_pct: float = 20.0          # a difference below this (in percent) is not flagged
    variance_min_units: float = 10.0    # ... nor one below this many units per week
    min_history_weeks: int = 26         # fewer closed weeks than this means fallbacks and low confidence
    min_recent_weeks: int = 3           # a recent run-rate needs at least this many closed weeks
    trend_weeks: int = 4                # compare the last N closed weeks with the N before
    trend_min_units: float = 20.0
    below_ratio: float = 0.85           # "consistently below" = actual under this share of forecast ...
    below_weeks: int = 3                # ... in at least this many closed plan weeks
    blend: float = 0.5                  # proposal = blend * recent run-rate + (1 - blend) * model forecast
    max_proposals: int = 10


def fid(kind: str, *parts: Any) -> str:
    """A stable id: the same finding on the same data keeps its id, so an adopt/dismiss decision stays attached to it."""
    return hashlib.sha1(json.dumps([kind, *parts], default=str, sort_keys=True).encode()).hexdigest()[:12]


def _r(v: float | None, d: int = 1) -> float | None:
    return None if v is None else round(float(v), d)


def _prov(source: str) -> list[str]:
    return ["DEMO", "FORECAST"] if source == "POC_SYNTHETIC" else ["LIVE", "FORECAST"]


def _freshness(ev: Mapping[str, Any]) -> dict[str, Any]:
    return {"data_through": ev.get("data_through"), "forecast_generated": ev.get("forecast_generated"), "plan_version": ev.get("plan", {}).get("version")}


def _finding(ev: Mapping[str, Any], kind: str, key: Sequence[Any], *, title: str, severity: str, statement: str, facts: list[str], hypotheses: list[str] | None = None,
             scope: dict[str, Any] | None = None, evidence: list[dict[str, Any]] | None = None, assumptions: list[str] | None = None, confidence: str = "MEDIUM",
             impact: str | None = None, risks: list[str] | None = None, next_action: str = "", proposal: dict[str, Any] | None = None, provenance: list[str] | None = None) -> dict[str, Any]:
    return {"id": fid(kind, ev["plan"]["id"], *key), "kind": kind, "title": title, "severity": severity, "statement": statement, "facts": facts, "hypotheses": hypotheses or [],
            "scope": scope or {}, "evidence": evidence or [], "provenance": provenance or _prov(ev["plan"]["source"]), "freshness": _freshness(ev), "assumptions": assumptions or [],
            "confidence": confidence, "impact": impact, "risks": risks or [], "next_action": next_action, "proposal": proposal}


def _units(v: float | None) -> str:
    return "n/a" if v is None else f"{v:,.1f}".rstrip("0").rstrip(".")


# ------------------------------------------------------------------------------------------------ 1. is the data good enough to forecast from?
def readiness(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    src = ev["plan"]["source"]
    weeks, lines = ev["history_weeks"], ev["lines"]
    out: list[dict[str, Any]] = []
    short = weeks < th.min_history_weeks
    low = lines["low_confidence"] or 0
    total = lines["lines"] or 0
    share = (low / total * 100) if total else 0.0
    if short or share >= 50:
        facts = [f"{weeks} closed week(s) of sales history are available; {th.min_history_weeks} are needed for the full method set.",
                 f"{low:,} of {total:,} plan lines ({share:.0f}%) have LOW confidence; {lines['no_forecast']:,} have no forecast."]
        out.append(_finding(ev, "DATA_READINESS", ("history",), title="Forecast evidence is thin" if src != "POC_SYNTHETIC" else "Demonstration forecasts on generated history",
                            severity="HIGH" if short and src != "POC_SYNTHETIC" else "MEDIUM",
                            statement=(f"Only {weeks} closed week(s) of history stand behind this plan, so most forecasts are low-confidence fallbacks and accuracy is not claimed." if src != "POC_SYNTHETIC"
                                       else f"This plan is built on generated demonstration history ({weeks} weeks). Its forecasts show how the method behaves; they are not predictions about real stores."),
                            facts=facts, hypotheses=[], scope={}, evidence=[{"label": "Data and forecasts", "tab": "readiness"}, {"label": "Forecast accuracy", "tab": "evaluation"}],
                            confidence="HIGH", impact="A provisional plan can be prepared, but it should be reviewed against local knowledge before approval.",
                            assumptions=["Closed weeks are weeks that ended on or before the data-through date."],
                            risks=["Targets set against low-confidence forecasts may be too high or too low."], next_action="Prepare a provisional plan with a visible warning, and add history (a controlled import or a store feed) to enable reliable forecasts."))
    unavailable: list[dict[str, str]] = []
    if src == "DSPORTS_LIVE":
        unavailable.append({"analysis": "Store-level analysis", "why": "Live data is online orders only; there are no physical-store sales, so no store can be analysed or ranked from real data.",
                            "needs": "A real store sales feed (store identifiers on each sale)."})
    return out, unavailable


# ------------------------------------------------------------------------------------------------ 2. does the forecast agree with what has recently been selling?
def _group_rows(rows: Sequence[Mapping[str, Any]], key: str) -> dict[str, dict[str, float]]:
    g: dict[str, dict[str, float]] = {}
    for r in rows:
        k = r[key]
        d = g.setdefault(k, {"f": 0.0, "r": 0.0, "lines": 0.0})
        d["f"] += float(r["f_avg"] or 0)
        d["r"] += float(r["r_sum"] or 0) / max(1, ev_weeks(r))
        d["lines"] += 1
    return g


def ev_weeks(r: Mapping[str, Any]) -> int:
    return int(r.get("n_recent") or 1)


def forecast_vs_recent(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows, n = ev["recent_rows"], ev["n_recent"]
    if n < th.min_recent_weeks or not rows:
        return [], [{"analysis": "Forecast versus recent sales", "why": f"It needs at least {th.min_recent_weeks} closed weeks before the plan starts; there are {n}.", "needs": "More closed weeks of sales history."}]
    for r in rows:
        r["n_recent"] = n
    out: list[dict[str, Any]] = []
    by_cat = _group_rows(rows, "category_label")
    for cat, d in sorted(by_cat.items(), key=lambda kv: -abs(kv[1]["f"] - kv[1]["r"])):
        diff = d["f"] - d["r"]
        if d["r"] <= 0 or abs(diff) < th.variance_min_units or abs(diff / d["r"] * 100) < th.variance_pct:
            continue
        pct = diff / d["r"] * 100
        lines_in = [r for r in rows if r["category_label"] == cat]
        proposal = _proposal(ev, lines_in, th)
        stockout = sum(float(r.get("stockout_days") or 0) for r in lines_in)
        hyp = ["Recent weeks may include a one-off order, a promotion or a stock-out that the history does not record.", "The model may be extrapolating a short history or a seasonal pattern that has not started yet."]
        if stockout:
            hyp.insert(0, f"{stockout:,.0f} stock-out day(s) were recorded in the recent weeks: low recent sales may reflect unavailable stock rather than weak demand.")
        out.append(_finding(ev, "FORECAST_VS_RECENT", (cat,), title=f"{cat}: forecast {'above' if diff > 0 else 'below'} recent sales", severity="HIGH" if abs(pct) >= 50 else "MEDIUM",
                            statement=f"{cat}: the plan forecasts {_units(d['f'])} units per week for the next {ev['plan_weeks']} week(s) against {_units(d['r'])} units per week sold over the last {n} closed weeks ({pct:+.0f}%).",
                            facts=[f"Forecast: {_units(d['f'])} units/week (plan weeks {ev['plan_first_week']} onward, approved or manager forecast where set, otherwise model).",
                                   f"Actual: {_units(d['r'])} units/week (net units, last {n} closed weeks to {ev['data_through']}).", f"Difference: {diff:+,.1f} units/week over {int(d['lines'])} product-store series."],
                            hypotheses=hyp, scope={"category": cat}, evidence=[{"label": f"Plan lines for {cat}", "tab": "workspace", "params": {"category": cat.split(' / ')[-1], "sport": cat.split(' / ')[0]}},
                                                                             {"label": "Actual sales", "tab": "actuals"}],
                            assumptions=["Recent weekly average is the mean of the last closed weeks (a week with no sales counts as zero only for live data).", "The forecast already reflects any manager adjustments."],
                            confidence="MEDIUM" if n >= 4 else "LOW", impact=f"If the recent run-rate persists, the plan is {abs(diff) * ev['plan_weeks']:,.0f} units {'above' if diff > 0 else 'below'} actual demand over {ev['plan_weeks']} week(s).",
                            risks=["Over-forecasting leaves excess stock; under-forecasting risks stock-outs."], next_action="Review the largest lines and, if you have local knowledge, adjust the manager forecast with a reason.", proposal=proposal))
        if len(out) >= 6:
            break
    return out, []


def _proposal(ev: Mapping[str, Any], lines_in: Sequence[Mapping[str, Any]], th: Thresholds) -> dict[str, Any] | None:
    cands = []
    for r in lines_in:
        f, rec = float(r["f_avg"] or 0), float(r["r_sum"] or 0) / max(1, ev["n_recent"])
        if r.get("model_avg") is None or f <= 0 or abs(f - rec) < 1.0 or abs((f - rec) / max(rec, 1e-9) * 100) < max(th.variance_pct, 30.0) or rec <= 0:
            continue
        cands.append((abs(f - rec), r, rec))
    if not cands:
        return None
    cands.sort(key=lambda c: -c[0])
    items = []
    for _gap, r, rec in cands[:th.max_proposals]:
        items.append({"product_id": r["product_id"], "location_id": r["location_id"], "name": r["name"], "store": r["location_id"], "model_per_week": _r(r["model_avg"]), "recent_per_week": _r(rec),
                      "proposed_per_week": _r(th.blend * rec + (1 - th.blend) * float(r["model_avg"]))})
    return {"kind": "MANAGER_FORECAST", "reason_code": "DATA_CORRECTION", "weeks": ev["plan_weeks_list"], "items": items,
            "basis": f"Proposed manager forecast = {th.blend:.0%} of the recent weekly run-rate + {1 - th.blend:.0%} of the model forecast, for each of the next {ev['plan_weeks']} week(s). A transparent blend, not a model output; a person decides.",
            "note": "AI-assisted proposal: blend of recent run-rate and model forecast"}


# ------------------------------------------------------------------------------------------------ 3. closed plan weeks: where actual and forecast diverge
def closed_variance(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows = [r for r in ev["variance_rows"] if r["with_actual"]]
    if not rows:
        return [], [{"analysis": "Plan versus actual variance", "why": "No week of this plan has closed with an actual yet, so there is nothing to compare.", "needs": "Wait for the plan weeks to close, or import closed-week actuals."}]
    out = []
    flagged = [r for r in rows if r["forecast_variance"] is not None and r["f_a"] and abs(r["forecast_variance"]) >= th.variance_min_units and abs(r["forecast_variance"] / r["f_a"] * 100) >= th.variance_pct]
    flagged.sort(key=lambda r: -abs(r["forecast_variance"]))
    for r in flagged[:6]:
        pct = r["forecast_variance"] / r["f_a"] * 100
        out.append(_finding(ev, "VARIANCE", (r["dim"], r["gkey"]), title=f"{r['gkey']}: {'above' if pct > 0 else 'below'} forecast", severity="HIGH" if abs(pct) >= 50 else "MEDIUM",
                            statement=f"{r['gkey']}: actual {_units(r['a_f'])} against forecast {_units(r['f_a'])} units over the closed weeks ({pct:+.0f}%, {r['forecast_variance']:+,.0f} units).",
                            facts=[f"Closed weeks with an actual: {r['with_actual']} of {r['lines']} lines.", f"Forecast variance = actual - forecast = {r['forecast_variance']:+,.1f} units."],
                            hypotheses=["Stock availability, a promotion, a price change or a data issue could each explain this; open the line investigation to see which of these the records can confirm."],
                            scope={r["dim"]: r["gkey"]}, evidence=[{"label": "Plan versus actual", "tab": "performance", "params": {"grain": r["dim"]}}], confidence="MEDIUM",
                            next_action="Open the investigation for the largest lines before changing the forecast."))
    return out, []


def below_forecast(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    if ev["plan"]["source"] == "DSPORTS_LIVE":
        return [], []
    rows = ev["store_weeks"]
    if not rows:
        return [], [{"analysis": "Stores consistently below forecast", "why": "It needs closed plan weeks with actuals.", "needs": "At least 3 closed weeks in the plan period."}]
    by: dict[str, list[tuple[float, float]]] = {}
    for r in rows:
        by.setdefault(r["location_id"], []).append((float(r["a"]), float(r["f"])))
    out = []
    for store, wk in by.items():
        if len(wk) < th.below_weeks:
            continue
        under = sum(1 for a, f in wk if f > 0 and a < th.below_ratio * f)
        if under >= th.below_weeks and under / len(wk) >= 0.75:
            tot_a, tot_f = sum(a for a, _ in wk), sum(f for _, f in wk)
            out.append(_finding(ev, "BELOW_FORECAST", (store,), title=f"{store} is repeatedly below forecast", severity="MEDIUM",
                                statement=f"{store} sold less than {th.below_ratio:.0%} of its forecast in {under} of {len(wk)} closed weeks ({_units(tot_a)} against {_units(tot_f)} units).",
                                facts=[f"{under} of {len(wk)} closed weeks were under {th.below_ratio:.0%} of forecast."], hypotheses=["Local availability or a store-specific demand level the model does not capture could be the cause."],
                                scope={"store": store}, evidence=[{"label": "Plan versus actual by store", "tab": "performance", "params": {"grain": "store"}}], confidence="MEDIUM", next_action="Investigate before lowering the target or the forecast."))
    return out[:5], []


# ------------------------------------------------------------------------------------------------ 4. category trends
def trends(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows = ev["trend_rows"]
    if ev["history_weeks"] < 2 * th.trend_weeks or not rows:
        return [], [{"analysis": "Category growth or decline", "why": f"It compares the last {th.trend_weeks} closed weeks with the {th.trend_weeks} before; there are {ev['history_weeks']} closed week(s).", "needs": f"At least {2 * th.trend_weeks} closed weeks of sales."}]
    out = []
    for r in sorted(rows, key=lambda r: -abs((r["last"] or 0) - (r["prior"] or 0))):
        last, prior = float(r["last"] or 0), float(r["prior"] or 0)
        if prior <= 0 or max(last, prior) < th.trend_min_units:
            continue
        pct = (last - prior) / prior * 100
        if abs(pct) < th.variance_pct:
            continue
        out.append(_finding(ev, "TREND", (r["category_label"],), title=f"{r['category_label']} is {'growing' if pct > 0 else 'declining'}", severity="LOW" if abs(pct) < 40 else "MEDIUM",
                            statement=f"{r['category_label']}: {_units(last)} units in the last {th.trend_weeks} closed weeks against {_units(prior)} in the {th.trend_weeks} before ({pct:+.0f}%).",
                            facts=[f"Net units, {th.trend_weeks}-week windows ending {ev['data_through']}."], hypotheses=["A seasonal pattern, an event or a change in availability could explain the movement."],
                            scope={"category": r["category_label"]}, evidence=[{"label": "Regions and categories", "tab": "analysis"}], confidence="MEDIUM" if ev["history_weeks"] >= 12 else "LOW",
                            next_action="Check whether the forecast for this category already reflects the movement."))
    return out[:5], []


# ------------------------------------------------------------------------------------------------ 5. stock risk and what to focus on
def stock_risk(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    alloc = ev["alloc"]
    if not alloc["generated"]:
        return [], [{"analysis": "Stock-out and excess risk", "why": "No inventory recommendation has been generated for this plan.", "needs": "Generate the allocation in Inventory planning (a draft; nothing is ordered)."}]
    out = []
    if alloc["high_risk"]:
        top = alloc["top_risks"]
        out.append(_finding(ev, "STOCK_RISK", ("high",), title=f"{alloc['high_risk']} position(s) at high stock-out risk", severity="HIGH",
                            statement=f"{alloc['high_risk']} product-store position(s) are at high stock-out risk; the largest shortfall is {top[0]['name']} at {top[0]['location_id']} ({_units(top[0]['shortage'])} units short).",
                            facts=[f"{t['name']} at {t['location_id']}: on hand {_units(t['on_hand'])}, incoming {_units(t['incoming'])}, shortage {_units(t['shortage'])}, cover {_units(t['coverage_days'])} days." for t in top[:5]],
                            hypotheses=[], scope={}, evidence=[{"label": "Inventory planning", "tab": "inventory", "params": {"risk": "HIGH"}}], provenance=["FORECAST"] + (["DEMO"] if ev["plan"]["source"] == "POC_SYNTHETIC" else ["LIVE"]),
                            confidence="MEDIUM", impact=f"Shortage across these positions: {_units(sum(t['shortage'] for t in top))} units (top {len(top)}).",
                            assumptions=["Sellable stock excludes reserved stock; incoming stock is counted once.", "Stock positions are " + ("synthetic demonstration stock." if ev["plan"]["source"] == "POC_SYNTHETIC" else "from the commerce inventory.")],
                            risks=["Recommendations are drafts; no order or transfer has been made."], next_action="Review the replenishment and transfer drafts; approving records a decision only."))
    if alloc["with_surplus"]:
        out.append(_finding(ev, "EXCESS_STOCK", ("surplus",), title=f"{alloc['with_surplus']} position(s) with excess stock", severity="LOW",
                            statement=f"{alloc['with_surplus']} position(s) hold more than the forecast needs; {_units(alloc['transfers_avoiding_orders'])} unit(s) of purchasing could be avoided by transfers.",
                            facts=[f"Units to order across the plan: {_units(alloc['units_to_order'])}.", f"Transfers proposed: {alloc['transfers']}."], scope={}, evidence=[{"label": "Transfers", "tab": "inventory"}], confidence="MEDIUM",
                            next_action="Prefer a transfer to a purchase where the draft shows one."))
    return out, []


def focus(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    rows = ev["focus_rows"]
    if not rows:
        return [], []
    top = rows[:5]
    return [_finding(ev, "FOCUS", ("next-week",), title="Where next week's sales are expected", severity="INFO",
                     statement=f"The largest forecasts for the week of {ev['plan_first_week']} are {', '.join(f'{r['name']} at {r['location_id']} ({_units(r['f'])})' for r in top[:3])}.",
                     facts=[f"{r['name']} at {r['location_id']}: {_units(r['f'])} units forecast, {r['confidence'] or 'no'} confidence." for r in top], scope={}, evidence=[{"label": "Weekly planning", "tab": "workspace"}], confidence="MEDIUM",
                     next_action="Check that stock for these lines is in place before the week starts.")], []


def build(ev: Mapping[str, Any], th: Thresholds) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    """All findings, most severe first, and the analyses that could not be run (with what would enable them)."""
    findings: list[dict[str, Any]] = []
    unavailable: list[dict[str, str]] = []
    for step in (readiness, forecast_vs_recent, closed_variance, below_forecast, trends, stock_risk, focus):
        f, u = step(ev, th)
        findings += f
        unavailable += u
    findings.sort(key=lambda x: (SEVERITY_ORDER[x["severity"]], x["kind"]))
    return findings, unavailable


def rules_summary(ev: Mapping[str, Any], findings: Sequence[Mapping[str, Any]], unavailable: Sequence[Mapping[str, str]]) -> str:
    """The deterministic executive summary, used when no LLM is available or its wording fails the grounding check."""
    src = "demonstration data" if ev["plan"]["source"] == "POC_SYNTHETIC" else "live online orders"
    head = f"Analysis of plan \"{ev['plan']['name']}\" on {src}, sales through {ev['data_through'] or 'no date'}."
    if not findings:
        return head + " No finding crossed a threshold."
    lines = [head] + [f"- {f['statement']}" for f in findings[:5]]
    if unavailable:
        lines.append(f"- {len(unavailable)} analysis(es) could not be run: " + "; ".join(u["analysis"] for u in unavailable[:4]) + ".")
    return "\n".join(lines)
