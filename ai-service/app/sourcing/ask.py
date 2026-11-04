"""Ask a question in plain words. Deterministic: the question is classified by rules, answered by calling the typed sourcing tools through the registry (so the role gate and argument
validation apply), and worded from the tool results with templates. No model is asked for a fact or a number, so nothing can be invented; anything the data does not contain
is reported as unavailable. Each answer lists the evidence it rests on."""
from __future__ import annotations

import re
from typing import Any

from app.sourcing import normalize
from app.tools.registry import ToolContext

SPORTS = ("badminton", "cricket", "football", "tennis", "running", "fitness", "basketball", "volleyball", "yoga", "swimming", "hockey", "gym")
_QTY = re.compile(r"(\d[\d,]*)\s*(?:units?|pcs|pieces)", re.I)
_MARGIN = re.compile(r"(\d{1,2}(?:\.\d+)?)\s*%\s*(?:gross\s*)?margin", re.I)


def _intent(q: str) -> str:
    t = q.lower()
    if re.search(r"season|event|tournament|world cup|olympic|league", t):
        return "SEASONAL"
    if re.search(r"customers?.*(look|search|ask|want)|not (currently )?sell|don'?t sell|unmet|missing from (our )?catalog|catalogue gap", t):
        return "DEMAND"
    if _MARGIN.search(t) or re.search(r"selling price|price (required|needed)|break.?even", t):
        return "PRICE_FOR_MARGIN"
    if re.search(r"compare|landed cost|best (supplier|quote|offer)|which supplier|cheapest", t):
        return "COMPARE"
    if re.search(r"why .*(score|priority|rank)|score", t):
        return "WHY_SCORE"
    if re.search(r"missing|evidence|before (we )?approve|what.*(need|else)", t):
        return "MISSING"
    if re.search(r"investigate|new .*products?|which products?|opportunit|worth", t):
        return "DISCOVER"
    if re.search(r"overview|summary|status|how many", t):
        return "OVERVIEW"
    return "UNKNOWN"


async def _call(registry, ctx: ToolContext, name: str, args: dict[str, Any]) -> tuple[Any, str | None]:
    r = await registry.call(name, args, ctx)
    ctx.calls = 0                                              # each question may use several lookups; the budget is per tool call here
    return (r.data, None) if r.ok else (None, r.error_message or r.error_code)


async def _find_opportunity(registry, ctx: ToolContext, question: str) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
    words = [w for w in normalize.tokens(question) if len(w) > 3 and w not in ("compare", "supplier", "suppliers", "units", "landed", "price", "selling", "margin", "gross", "quotes", "offers", "which", "what", "give", "best")]
    seen: dict[str, dict[str, Any]] = {}
    for w in words[:6]:
        data, _ = await _call(registry, ctx, "search_product_opportunities", {"query": w, "limit": 10})
        for o in (data or {}).get("opportunities", []):
            seen[o["id"]] = o
    if not seen:
        data, _ = await _call(registry, ctx, "search_product_opportunities", {"limit": 10})
        cands = (data or {}).get("opportunities", [])
        return (cands[0], cands) if len(cands) == 1 else (None, cands)
    named = [o for o in seen.values() if normalize.normalize_name(o["name"]) and normalize.normalize_name(o["name"]) in normalize.normalize_name(question)]
    if len(named) == 1:
        return named[0], named                                  # the question contains one product's full name: no ambiguity
    ranked = sorted(seen.values(), key=lambda o: -normalize.similarity(question, o["name"]))
    if len(ranked) == 1 or normalize.similarity(question, ranked[0]["name"]) > normalize.similarity(question, ranked[1]["name"]) + 0.15:
        return ranked[0], ranked
    return None, ranked


async def answer(registry, ctx: ToolContext, question: str) -> dict[str, Any]:
    intent = _intent(question)
    out: dict[str, Any] = {"intent": intent, "answer": "", "evidence": [], "limitations": [], "data": None, "needs_choice": []}

    if intent == "SEASONAL":
        out["answer"] = "I cannot answer that from evidence: no sporting-event or season calendar source is configured, so I do not guess which products suit an upcoming season."
        out["limitations"].append("Seasonal opportunities need an event calendar source (not configured).")
        return out
    if intent == "UNKNOWN":
        out["answer"] = ("I can answer questions about product opportunities, customer searches that found nothing, supplier offers and landed cost, scores and missing evidence. For example: "
                         "'Which new badminton products should we investigate?', 'What are customers looking for that we do not sell?', 'Compare the offers for the carbon racket at 500 units', "
                         "'What selling price gives a 30% gross margin for the carbon racket at 500 units?', 'Why did the carbon racket get this score?'")
        return out

    if intent == "OVERVIEW":
        data, err = await _call(registry, ctx, "get_sourcing_overview", {})
        if err:
            out["answer"] = f"I could not read the overview: {err}"
            return out
        out["data"] = data
        gaps = (data.get("demand_gaps") or {}).get("total", 0)
        out["answer"] = (f"There are {data['total_products']} live product candidates (demonstration and test records excluded): {data['awaiting_review']} awaiting review, {data['shortlisted']} shortlisted, "
                         f"{data.get('approved_opportunity', 0)} approved as a business opportunity, {data.get('approved_for_onboarding', 0)} approved for onboarding preparation. "
                         f"{data['with_supplier_quotes']} have supplier offers, {data.get('with_product_image', 0)} have a verified product image and {data['blocked_by_missing_information']} are blocked by missing information. "
                         f"{gaps} customer demand gap(s) are tracked separately because they are not products.")
        return out

    if intent == "DEMAND":
        data, err = await _call(registry, ctx, "get_unmet_demand", {"days": ctx_days(ctx), "limit": 10})
        if err:
            out["answer"] = f"I could not read customer demand: {err}"
            return out
        out["data"] = data
        if not data["groups"]:
            out["answer"] = f"No customer search that found nothing has been recorded in the last {data['window_days']} days, so there is no unmet-demand evidence to report."
        else:
            lines = [f"'{g['phrase']}': {g['searches']} search(es) from {g['customers']} customer(s), {g['first']} to {g['last']}" for g in data["groups"]]
            out["answer"] = f"Searches that found no product in the last {data['window_days']} days: " + "; ".join(lines) + ". This is customer interest, not proven purchase intent."
        out["limitations"].append(data["note"])
        return out

    if intent == "DISCOVER":
        sport = next((s for s in SPORTS if s in question.lower()), None)
        data, err = await _call(registry, ctx, "search_product_opportunities", {"sport": sport.capitalize() if sport else None, "limit": 10})
        if err:
            out["answer"] = f"I could not search opportunities: {err}"
            return out
        out["data"] = data
        if not data["opportunities"]:
            out["answer"] = "No matching opportunity is recorded yet. Run research from the Research runs tab, or add a product by hand; I do not make up candidates."
        else:
            out["answer"] = f"{data['total']} opportunity(ies)" + (f" for {sport}" if sport else "") + ": " + "; ".join(
                f"{o['name']} ({o['status'].replace('_', ' ').lower()}{', DEMO DATA' if o['demo'] else ''}{'' if o['score'] is None else ', score ' + str(round(o['score']))})" for o in data["opportunities"]) + "."
        out["limitations"].append(data["note"] if data["opportunities"] else "No data.")
        return out

    # the remaining intents are about one opportunity
    opp, candidates = await _find_opportunity(registry, ctx, question)
    if opp is None:
        out["needs_choice"] = [{"id": c["id"], "name": c["name"]} for c in candidates[:8]]
        out["answer"] = "Which product do you mean? " + (", ".join(c["name"] for c in candidates[:8]) if candidates else "No opportunity matches your wording; add or search for it first.")
        return out
    out["opportunity"] = {"id": opp["id"], "name": opp["name"]}
    if intent in ("COMPARE", "PRICE_FOR_MARGIN"):
        m = _QTY.search(question)
        if not m:
            out["answer"] = f"At what order quantity should I compare the offers for {opp['name']}? (for example '500 units')"
            return out
        qty = int(m.group(1).replace(",", ""))
        data, err = await _call(registry, ctx, "compare_supplier_offers", {"opportunity_id": opp["id"], "quantity": qty})
        if err:
            out["answer"] = f"I could not compare the offers: {err}"
            return out
        out["data"] = data
        offers = data["offers"]
        if not offers:
            out["answer"] = f"No supplier offer is recorded for {opp['name']}. Record one from a supplier quotation first."
            return out
        if intent == "PRICE_FOR_MARGIN":
            tm = _MARGIN.search(question)
            target = float(tm.group(1)) if tm else None
            lines = []
            for o in offers:
                c = o["calculation"]
                if c["status"] != "OK":
                    lines.append(f"{o['quote']['supplier_name']}: not calculable ({'; '.join(c['issues'] or c['missing'])})")
                    continue
                if target is None:
                    lines.append(f"{o['quote']['supplier_name']}: break-even selling price {c['values']['break_even_selling_price']}")
                else:
                    r = await _target(registry, ctx, opp["id"], o["quote"]["id"], qty, target)
                    lines.append(f"{o['quote']['supplier_name']}: {r}")
            out["answer"] = f"For {opp['name']} at {qty} units" + (f" and a {target:g}% gross margin" if target else "") + " (selling price before any tax treatment you set): " + "; ".join(lines) + "."
        else:
            usable = [o for o in offers if o["calculation"]["status"] == "OK" and o["quote"]["status"] == "ACTIVE"]
            if not usable:
                out["answer"] = f"None of the {len(offers)} offer(s) for {opp['name']} can be compared at {qty} units: " + "; ".join(
                    f"{o['quote']['supplier_name']} ({'; '.join(o['calculation']['issues'] or o['calculation']['missing'] or [o['quote']['status']])})" for o in offers) + "."
            else:
                best = sorted(usable, key=lambda o: o["rank_by_landed_cost"] or 99)
                out["answer"] = (data["summary"]["explanation"] or "") + " Landed cost per sellable unit: " + "; ".join(
                    f"{o['quote']['supplier_name']} {o['calculation']['values']['landed_cost_per_unit']} ({o['quote']['label'].lower()})" for o in best) + "."
        out["evidence"] = [{"supplier": o["quote"]["supplier_name"], "source": o["quote"]["label"], "reference": o["quote"].get("reference")} for o in offers]
        out["limitations"].append("Figures use the saved assumptions you provided; defaults are zero, not estimates.")
        return out

    data, err = await _call(registry, ctx, "get_opportunity_score", {"opportunity_id": opp["id"]})
    if err:
        out["answer"] = f"I could not score it: {err}"
        return out
    ev, _ = await _call(registry, ctx, "get_opportunity_evidence", {"opportunity_id": opp["id"]})
    out["data"] = data
    out["evidence"] = [{"claim": e["claim"], "source": e["source"], "url": e.get("url")} for e in (ev or {}).get("evidence", [])]
    if intent == "WHY_SCORE":
        if data["status"] != "SCORED":
            out["answer"] = f"{opp['name']} has insufficient evidence for a score. " + " ".join(data["reasons"])
        else:
            out["answer"] = (f"{opp['name']} scores {data['opportunity']}/100 (priority {data['priority']}, evidence confidence {data['confidence'].lower()}, supplier risk {data['supplier_risk']['level'].lower()}). "
                             + " ".join(data["reasons"]))
    else:
        missing = [m.replace("_", " ") for m in data["missing"]]
        out["answer"] = (f"Before approving {opp['name']}: no data for {', '.join(missing) or 'nothing listed'}; supplier risk is {data['supplier_risk']['level'].lower()} "
                         f"({'; '.join(data['supplier_risk']['factors'])}). Unanswered: {'; '.join((ev or {}).get('unanswered', [])) or 'none recorded'}. Next: {data['next_action']}")
    return out


def ctx_days(ctx: ToolContext) -> int:
    return int(getattr(ctx.settings, "sourcing_lookback_days", 30))


async def _target(registry, ctx: ToolContext, opp_id: str, quote_id: str, qty: int, target: float) -> str:
    svc = ctx.services.sourcing
    from app.sourcing.service import Actor
    res = await svc.calculate(Actor(ctx.principal.customer_hash, list(ctx.principal.roles)), opp_id, {"quote_id": quote_id, "quantity": qty, "assumptions": {"target_margin_pct": target}, "sensitivity": False}, False)
    if res["status"] != "OK":
        return f"not calculable ({'; '.join(res['issues'] or res['missing'])})"
    return f"selling price {res['values'].get('target_margin_selling_price')} (landed cost {res['values']['landed_cost_per_unit']})"
