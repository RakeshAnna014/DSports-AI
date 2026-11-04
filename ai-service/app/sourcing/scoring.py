"""Explainable, versioned opportunity scoring (model v2).

Five separate outputs, so a high opportunity can never hide weak evidence, an incomplete record, a risky supplier or a missing financial basis:
  * opportunity       0-100, weighted from the dimensions that have data (weights are re-normalised over the available ones)
  * confidence        LOW / MEDIUM / HIGH: how much of the model could be evaluated, how fresh and independent the evidence is, whether a quotation was entered or verified
  * data_completeness the share of the model's weight that had data
  * supplier_risk     LOW / MEDIUM / HIGH / UNKNOWN from verification, authorisation, quote freshness, lead time and exposure
  * financial_status  QUOTED (margin from a quotation a buyer entered or verified) | ESTIMATED (from a public listing) | INCOMPLETE | NONE
Special cases (no number is invented):
  * a customer-search DEMAND_GAP is "a demand signal awaiting product discovery": it is not a product and is not scored
  * too little data gives status INSUFFICIENT_EVIDENCE ("Not enough evidence") and no number
  * fixture data is labelled as such; legacy rows of unknown provenance never reach high confidence
No LLM is involved.
"""
from __future__ import annotations

import math
from datetime import datetime, timezone
from typing import Any, Mapping

MODEL_VERSION = "v2"
DEFAULT_WEIGHTS: dict[str, float] = {
    "unmet_demand": 0.20, "catalogue_gap": 0.14, "market_evidence": 0.14, "product_identity": 0.08, "margin": 0.20, "supplier_availability": 0.10, "lead_time": 0.05, "moq_exposure": 0.04,
    "inventory_signal": 0.05,
}
MIN_AVAILABLE_WEIGHT = 0.35          # below this share of the model the opportunity is not scored at all
GAP_SCORE = {"NONE": 100.0, "SIMILAR": 45.0, "REVIEW": 30.0, "PROBABLE_DUPLICATE": 10.0, "EXACT": 0.0}
GAP_WHY = {"NONE": "Nothing similar is in the catalogue.", "SIMILAR": "A related product exists; this may extend the range rather than fill a gap.",
           "REVIEW": "The catalogue match is ambiguous and needs a person to decide.", "PROBABLE_DUPLICATE": "A very similar product is probably already sold (to be confirmed).",
           "EXACT": "DSports already sells this product."}
FIXTURES = ("DEMO_FIXTURE", "TEST_FIXTURE")


def _clip(x: float) -> float:
    return max(0.0, min(100.0, x))


def _age_days(ts: datetime | None, now: datetime) -> float | None:
    return None if ts is None else max(0.0, (now - ts).total_seconds() / 86400)


def score(signals: Mapping[str, Any], weights: Mapping[str, float] | None = None, now: datetime | None = None) -> dict[str, Any]:
    """signals (every key optional; absent means 'no data', never 'zero'):
        record_kind: 'PRODUCT_CANDIDATE' | 'DEMAND_GAP';  provenance: see schema_sourcing.sql
        unmet_searches, unmet_customers                  distinct searches / hashed customers who did not find it (customer interest, not purchase intent)
        catalogue_match: NONE | SIMILAR | REVIEW | PROBABLE_DUPLICATE | EXACT
        evidence: [{'id': int, 'source': str, 'verified_at': datetime, 'source_type': 'OFFICIAL_BRAND_PAGE' | ...}]   product-level evidence (not customer searches)
        identity: {'brand': bool, 'identifier': bool, 'description': bool, 'specs': int, 'image': 'EXACT_MODEL' | 'LIKELY_MATCH' | ... | None}
        gross_margin_pct: float, financial: {'status': 'QUOTED' | 'ESTIMATED' | 'INCOMPLETE' | 'NONE', 'basis': {...}}
        quotes: [{'status': 'ACTIVE' | ..., 'verification': 'VERIFIED' | 'QUOTE_ENTERED' | 'PUBLIC_LISTING', 'authorised': bool | None, 'lead_time_days': int | None, 'moq_cost': float | None}]
        working_capital_limit, inventory_signal
    """
    w = dict(weights or DEFAULT_WEIGHTS)
    now = now or datetime.now(timezone.utc)
    prov = signals.get("provenance")
    fin = dict(signals.get("financial") or {"status": "NONE"})
    base = {"model_version": MODEL_VERSION, "provenance": prov, "financial_status": fin.get("status", "NONE"), "financial_basis": fin.get("basis")}
    total_w = sum(w.values())
    weights_out = {k: round(v / total_w, 3) for k, v in w.items()}

    if signals.get("record_kind") == "DEMAND_GAP":
        n, s = int(signals.get("unmet_customers") or 0), int(signals.get("unmet_searches") or 0)
        return {**base, "status": "AWAITING_PRODUCT_DISCOVERY", "opportunity": None, "priority": None, "confidence": "LOW", "data_completeness": 0.0, "dimensions": [], "missing": list(w),
                "supplier_risk": {"level": "UNKNOWN", "factors": ["There is no product or supplier yet."]},
                "reasons": [f"This is a customer demand signal ({s} search(es) from {n} distinct customer(s)), not a product. It is awaiting product discovery: find or link a real product that fits it."],
                "next_action": "Find a real product that answers this demand (run research, or link a product candidate), then evaluate that product.", "weights": weights_out}

    dims: dict[str, dict[str, Any]] = {}
    evidence = list(signals.get("evidence") or [])
    quotes = list(signals.get("quotes") or [])
    live_quotes = [q for q in quotes if q.get("status") == "ACTIVE"]

    if signals.get("unmet_searches") is not None:
        n = max(int(signals.get("unmet_customers") or 0), 0)
        s = int(signals["unmet_searches"])
        dims["unmet_demand"] = {"score": _clip(100 * math.log1p(n or min(s, 1)) / math.log1p(15)) if s else 0.0,
                                "why": f"{s} unmet search(es) from {n} distinct customer(s) in the window. This is customer interest, not proven purchase intent."}
    if signals.get("catalogue_match") in GAP_SCORE:
        m = signals["catalogue_match"]
        dims["catalogue_gap"] = {"score": GAP_SCORE[m], "why": GAP_WHY[m]}
    if evidence:
        sources = {e.get("source") for e in evidence if e.get("source")}
        ages = [a for a in (_age_days(e.get("verified_at"), now) for e in evidence) if a is not None]
        freshest = min(ages) if ages else None
        fresh = 1.0 if freshest is not None and freshest <= 30 else 0.6 if freshest is not None and freshest <= 90 else 0.3
        official = any(e.get("source_type") in ("OFFICIAL_BRAND_PAGE", "AUTHORISED_DISTRIBUTOR") for e in evidence)
        dims["market_evidence"] = {"score": _clip((min(len(sources), 3) / 3 * 70 + (30 if official else 0)) * fresh),
                                   "why": f"{len(sources)} independent source(s)" + (", including an official brand or distributor page" if official else "")
                                          + (f", newest checked {freshest:.0f} day(s) ago." if freshest is not None else ", check dates unknown.")}
    ident = signals.get("identity")
    if ident:
        parts = [bool(ident.get("brand")), bool(ident.get("identifier")), bool(ident.get("description")), int(ident.get("specs") or 0) >= 3,
                 ident.get("image") in ("EXACT_MODEL", "LIKELY_MATCH")]
        dims["product_identity"] = {"score": 100.0 * sum(parts) / len(parts),
                                    "why": "Identity completeness: " + ", ".join(f"{n} {'yes' if ok else 'missing'}" for n, ok in zip(("brand", "model/SKU/GTIN", "description", "specifications", "product image"), parts)) + "."}
    if signals.get("gross_margin_pct") is not None and fin.get("status") in ("QUOTED", "ESTIMATED"):
        gm = float(signals["gross_margin_pct"])
        label = "from a quotation a buyer entered or verified" if fin.get("status") == "QUOTED" else "ESTIMATED from a public listing price (not a quotation)"
        dims["margin"] = {"score": _clip(gm / 40 * 100), "why": f"Gross margin {gm:.1f}% {label} (scale: 0% scores 0, 40% or more scores 100)."}
    if quotes:
        verified = [q for q in live_quotes if q.get("verification") in ("VERIFIED", "QUOTE_ENTERED")]
        s = 0.0 if not live_quotes else 40.0 + (40.0 if verified else 0.0) + (20.0 if len(live_quotes) > 1 else 0.0)
        dims["supplier_availability"] = {"score": s, "why": f"{len(live_quotes)} active offer(s), {len(verified)} entered or verified by a buyer; {len(quotes) - len(live_quotes)} expired or inactive."}
        leads = [q["lead_time_days"] for q in live_quotes if q.get("lead_time_days") is not None]
        if leads:
            best = min(leads)
            dims["lead_time"] = {"score": _clip(100 * (90 - best) / 76) if best > 14 else 100.0, "why": f"Shortest quoted lead time {best} days (14 days or less scores 100, 90 or more scores 0)."}
        limit = signals.get("working_capital_limit")
        costs = [q["moq_cost"] for q in live_quotes if q.get("moq_cost") is not None]
        if limit and costs:
            ratio = min(costs) / float(limit)
            dims["moq_exposure"] = {"score": _clip(100 * (1 - ratio)), "why": f"Smallest minimum order costs {ratio * 100:.0f}% of the configured working-capital limit."}
    if signals.get("inventory_signal") is not None:
        dims["inventory_signal"] = {"score": _clip(float(signals["inventory_signal"])), "why": "Signal supplied by Inventory AI (this module does not re-forecast)."}

    available_w = sum(w[k] for k in dims if k in w)
    share = available_w / total_w if total_w else 0.0
    rows = [{"dimension": k, "score": round(v["score"], 1), "weight": round(w.get(k, 0) / total_w, 3), "why": v["why"]} for k, v in dims.items() if k in w]
    missing = [k for k in w if k not in dims]
    risk = supplier_risk(quotes, now)
    notes = ["Fixture data: this is not a live business score."] if prov in FIXTURES else []
    refs = [e["id"] for e in evidence if e.get("id") is not None]

    if (not evidence and not dims) or share < MIN_AVAILABLE_WEIGHT:
        return {**base, "status": "INSUFFICIENT_EVIDENCE", "opportunity": None, "priority": None, "confidence": "LOW", "data_completeness": round(share, 2), "dimensions": rows, "missing": missing,
                "supplier_risk": risk, "reasons": notes + ["Not enough evidence: too little of the model could be evaluated to give a number."], "evidence_refs": refs,
                "next_action": next_action(missing, risk, insufficient=True), "weights": weights_out}

    opp = sum(r["score"] * w[r["dimension"]] for r in rows) / available_w
    ages = [a for a in (_age_days(e.get("verified_at"), now) for e in evidence) if a is not None]
    verified_quote = any(q.get("verification") in ("VERIFIED", "QUOTE_ENTERED") and q.get("status") == "ACTIVE" for q in quotes)
    conf_points = share + (0.2 if ages and min(ages) <= 30 else 0.0) + (0.2 if verified_quote else 0.0) + (0.1 if len({e.get("source") for e in evidence}) >= 2 else 0.0)
    confidence = "HIGH" if conf_points >= 1.0 else "MEDIUM" if conf_points >= 0.65 else "LOW"
    if prov == "UNKNOWN_PROVENANCE":
        confidence = "LOW"
        notes.append("Provenance is unknown: confidence is capped at LOW until a buyer reviews where this record came from.")
    priority = "HIGH" if opp >= 70 and confidence != "LOW" and risk["level"] != "HIGH" else "MEDIUM" if opp >= 45 else "LOW"
    ranked = sorted(rows, key=lambda r: r["score"] * r["weight"], reverse=True)
    reasons = notes + [f"{r['dimension'].replace('_', ' ')}: {r['score']:.0f}/100. {r['why']}" for r in ranked[:3]]
    reasons += [f"Held back by {r['dimension'].replace('_', ' ')} ({r['score']:.0f}/100)." for r in [r for r in rows if r["score"] < 35][:2]]
    return {**base, "status": "SCORED", "opportunity": round(opp, 1), "priority": priority, "confidence": confidence, "data_completeness": round(share, 2), "dimensions": rows, "missing": missing,
            "supplier_risk": risk, "reasons": reasons, "evidence_refs": refs, "next_action": next_action(missing, risk, insufficient=False), "weights": weights_out}


def supplier_risk(quotes: list[Mapping[str, Any]], now: datetime | None = None) -> dict[str, Any]:
    if not quotes:
        return {"level": "UNKNOWN", "factors": ["No supplier quotation or listing has been recorded yet."]}
    live = [q for q in quotes if q.get("status") == "ACTIVE"]
    factors: list[str] = []
    points = 0
    if not live:
        return {"level": "HIGH", "factors": ["Every recorded quotation has expired or is inactive."]}
    if not any(q.get("verification") in ("VERIFIED", "QUOTE_ENTERED") for q in live):
        points += 2
        factors.append("No quotation has been entered from a supplier document or verified by a buyer (public listings only).")
    if all(q.get("authorised") is None for q in live):
        points += 1
        factors.append("Brand authorisation or distributor status has not been checked.")
    elif not any(q.get("authorised") for q in live):
        points += 2
        factors.append("No supplier is confirmed as authorised for the brand.")
    leads = [q["lead_time_days"] for q in live if q.get("lead_time_days") is not None]
    if not leads:
        points += 1
        factors.append("Lead time is unknown.")
    elif min(leads) > 60:
        points += 1
        factors.append(f"Shortest lead time is {min(leads)} days.")
    if len(live) == 1:
        points += 1
        factors.append("Only one active supplier: no comparison and no fallback.")
    return {"level": "HIGH" if points >= 4 else "MEDIUM" if points >= 2 else "LOW", "factors": factors or ["No risk factor found in the recorded information (this is not a guarantee)."]}


def next_action(missing: list[str], risk: Mapping[str, Any], insufficient: bool) -> str:
    if insufficient:
        return "Gather evidence first: run research for this product, check customer demand and record at least one supplier quotation."
    if "margin" in missing:
        return "Enter a supplier quotation and a target selling price so the margin can be calculated."
    if risk["level"] in ("UNKNOWN", "HIGH"):
        return "Verify the supplier (authorisation, a current quotation) before taking this further."
    if "unmet_demand" in missing:
        return "Check whether customers are asking for this product before committing to a quantity."
    return "Ready for buyer review: compare suppliers at the intended order quantity."
