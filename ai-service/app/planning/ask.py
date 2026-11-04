"""Ask a planning question in plain words. Deterministic: the question is classified by rules, answered by calling the typed planning tools through the registry (so the role gate, the
argument validation and the store scope all apply), and worded from the tool results with templates. No model supplies a fact or a number, so nothing can be invented; anything the
data does not contain is reported as unavailable. Every answer lists the records it rests on, with a link to them in the planning screens."""
from __future__ import annotations

import re
from typing import Any

from app.tools.registry import ToolContext

SPORTS = ("cricket", "football", "badminton", "basketball", "cycling", "fitness", "running", "tennis", "volleyball", "hockey")
CATEGORIES = {"bat": "Bat", "bats": "Bat", "ball": "Ball", "balls": "Ball", "shoe": "Shoes", "shoes": "Shoes", "jersey": "Jersey", "jerseys": "Jersey", "helmet": "Helmet", "helmets": "Helmet",
              "glove": "Gloves", "gloves": "Gloves", "bag": "Kit Bag", "bags": "Kit Bag", "accessories": "Accessories", "accessory": "Accessories", "equipment": "Equipment", "bike": "Bike", "bikes": "Bike",
              "racket": "Racket", "rackets": "Racket"}
REGIONS = {"north": "N", "south": "S", "east": "E", "west": "W", "central": "C"}
STORE = re.compile(r"\b([NSEWC]-Z\d-S\d)\b", re.I)
NUMBER = re.compile(r"(\d+(?:\.\d+)?)")


def _intent(q: str) -> str:
    t = q.lower()
    if re.search(r"key risks|next (four|4) weeks|management attention|needs? attention|focus on|focus next|should .* focus|growing|declining|insights?|unreliable|analy[sz]e", t):
        return "ANALYSIS"
    if re.search(r"pilot|newly approved|new product", t):
        return "PILOT"
    if re.search(r"lead time|safety stock|what happens|what if|scenario|promotion|discount|growth", t):
        return "SCENARIO"
    if re.search(r"transfer", t):
        return "TRANSFER"
    if re.search(r"insufficient|not enough data|readiness|data quality|how reliable|is the data|real data|synthetic|demo", t):
        return "DATA"
    if re.search(r"accura|how good|bias|wape|backtest", t):
        return "ACCURACY"
    if re.search(r"run out|stock-?out|out of stock|at risk|shortage|running low", t):
        return "RISK"
    if re.search(r"allocat|how many units|replenish|reorder|re-order|how much stock|send to each store|purchase", t):
        return "ALLOCATE"
    if re.search(r"\bwhy\b", t):
        return "WHY"
    if re.search(r"missing .*target|miss .*target|below target|under target|consistently", t):
        return "TARGETS"
    if re.search(r"compare|versus|\bvs\b|actual|target|attainment|variance", t):
        return "COMPARE"
    if re.search(r"perform best|best seller|top product|prefer|popular|mix|by region|region", t):
        return "REGIONAL"
    if re.search(r"forecast|likely to sell|next week|expect", t):
        return "FORECAST"
    if re.search(r"overview|summary|status|how are we", t):
        return "OVERVIEW"
    return "UNKNOWN"


def _scope(q: str) -> dict[str, Any]:
    t = q.lower()
    out: dict[str, Any] = {}
    if sport := next((s for s in SPORTS if s in t), None):
        out["sport"] = sport.capitalize()
    words = re.findall(r"[a-z]+", t)
    if cat := next((CATEGORIES[w] for w in words if w in CATEGORIES), None):
        out["category"] = cat
    if reg := next((code for name, code in REGIONS.items() if re.search(rf"\b{name}\b", t)), None):
        out["region"] = reg
    if m := STORE.search(q):
        out["store"] = m.group(1).upper()
    if "live" in t or "online" in t:
        out["source"] = "DSPORTS_LIVE"
    return out


async def _call(registry, ctx: ToolContext, name: str, args: dict[str, Any]) -> tuple[Any, str | None]:
    r = await registry.call(name, args, ctx)
    ctx.calls = 0
    return (r.data, None) if r.ok else (None, r.error_message or r.error_code)


def link(tab: str, **p: Any) -> str:
    q = "&".join(f"{k}={v}" for k, v in {"tab": tab, **{k: v for k, v in p.items() if v}}.items())
    return f"/sales-planning?{q}"


def _fmt(v: Any, places: int = 0) -> str:
    return "n/a" if v is None else f"{v:,.{places}f}"


def _banner(data_scope_synthetic: bool) -> str:
    return " (DEMONSTRATION data: synthetic stores and sales, not validated business predictions)" if data_scope_synthetic else ""


async def answer(registry, ctx: ToolContext, question: str) -> dict[str, Any]:
    intent = _intent(question)
    scope = _scope(question)
    source = scope.pop("source", "POC_SYNTHETIC")
    synthetic = source == "POC_SYNTHETIC"
    out: dict[str, Any] = {"intent": intent, "answer": "", "evidence": [], "limitations": [], "data": None}

    async def fail(err: str) -> dict[str, Any]:
        out["answer"] = f"I could not read that: {err}"
        return out

    if intent == "UNKNOWN":
        out["answer"] = ("I can answer from the planning records: forecasts, plan versus actual, forecast accuracy, stock-out risk, how many units to allocate or replenish, transfers, regional performance, "
                         "what-if scenarios, new-product pilots and how reliable the data is. For example: 'Compare forecast, target and actual for badminton', 'Which stores are at risk of running out of cricket balls?', "
                         "'How accurate were the forecasts?', 'What happens if supplier lead time increases by 7 days for cricket bats?'")
        return out

    if intent == "ANALYSIS":
        data, err = await _call(registry, ctx, "get_planning_analysis", {"source": source, **{k: v for k, v in scope.items() if k in ("sport", "category", "region", "store")}})
        if err:
            return await fail(err)
        findings = data.get("findings", [])
        out["data"] = {"analysis_id": data.get("id"), "created_at": data.get("created_at"), "data_through": data.get("data_through"), "findings": [{k: f[k] for k in ("id", "kind", "title", "severity", "statement", "confidence", "provenance")} for f in findings[:8]],
                       "unavailable": data.get("unavailable", [])}
        if not findings:
            out["answer"] = "The analysis found nothing that crosses its thresholds" + _banner(synthetic) + "."
        else:
            top = [f for f in findings if f["severity"] != "INFO"][:4] or findings[:3]
            out["answer"] = "Observed in the stored records: " + " ".join(f"({i}) {f['statement']}" for i, f in enumerate(top, 1)) + _banner(synthetic)
        out["evidence"] = [{"label": f["title"], "link": link(ev["tab"], **(ev.get("params") or {}))} for f in findings[:5] for ev in f["evidence"][:1]]
        out["limitations"] = [u["analysis"] + ": " + u["why"] for u in data.get("unavailable", [])][:4] + [h for f in findings[:3] for h in f["hypotheses"][:1]]
        return out
    if intent == "DATA":
        data, err = await _call(registry, ctx, "get_data_readiness", {})
        if err:
            return await fail(err)
        out["data"] = data
        weak = [f"{c['label']}: {c['status'].replace('_', ' ').lower()}" for c in data["capabilities"] if c["status"] != "RELIABLE"]
        out["answer"] = ("Not every input is real. " + "; ".join(weak) + ". Forecasts: demonstration data " + data["forecasting"]["POC_SYNTHETIC"]["status"].replace("_", " ").lower() +
                         f"; live online data {data['forecasting']['DSPORTS_LIVE']['status'].replace('_', ' ').lower()} ({data['forecasting']['DSPORTS_LIVE']['reason']})")
        out["evidence"] = [{"label": "Data readiness", "link": link("readiness")}]
        out["limitations"] = [c["note"] for c in data["capabilities"] if c["status"] in ("DEMONSTRATION_ONLY", "UNAVAILABLE")][:4]
        return out

    if intent == "ACCURACY":
        data, err = await _call(registry, ctx, "get_forecast_accuracy", {"source": source, "scope": "OVERALL"})
        if err:
            return await fail(err)
        out["data"] = data
        sel = next((r for r in data["rows"] if r["model"] == "SELECTED" and r["horizon_group"] == "ALL"), None)
        if sel is None or not sel["sufficient"]:
            out["answer"] = "There is not enough held-out history to quote a forecast accuracy: " + ((sel or {}).get("note") or "no evaluation exists for this data scope.")
        else:
            better = sel["wape"] < sel["baseline_wape"]
            out["answer"] = (f"On the last {data['run']['horizon_weeks']} weeks held out from the model, the weighted absolute percentage error (WAPE) is {sel['wape'] * 100:.0f}% against {sel['baseline_wape'] * 100:.0f}% for a plain "
                             f"4-week average ({'better' if better else 'not better'} than the baseline), with a bias of {sel['bias'] * 100:+.1f}% ({'over' if sel['bias'] > 0 else 'under'}-forecast), over "
                             f"{sel['n_obs']:,} series-weeks{_banner(synthetic)}.")
        out["evidence"] = [{"label": "Forecast evaluation", "link": link("evaluation")}]
        out["limitations"] = [data["notice"]]
        return out

    if intent == "REGIONAL":
        data, err = await _call(registry, ctx, "get_regional_analysis", {"source": source, "weeks": 26, "region": scope.get("region")})
        if err:
            return await fail(err)
        out["data"] = {k: data[k] for k in ("weeks", "insights", "top_products")}
        top = data["top_products"][:5]
        ins = [i for i in data["insights"] if i["confidence"] == "MEDIUM"][:3] or data["insights"][:3]
        out["answer"] = ("Best-selling products over the last 26 closed weeks" + (f" in region {scope['region']}" if scope.get("region") else "") + ": " + "; ".join(f"{p['name']} ({p['net_units']:,.0f} units in {p['stores']} stores)" for p in top) +
                         ". " + (" ".join(i["statement"] for i in ins) if ins else "No region differs from the overall mix by 25% or more.") + _banner(synthetic))
        out["evidence"] = [{"label": "Regional analysis", "link": link("regional")}]
        out["limitations"] = [data["notice"]]
        return out

    if intent == "PILOT":
        data, err = await _call(registry, ctx, "get_scenarios_and_pilots", {})
        if err:
            return await fail(err)
        pilots = data["pilots"]
        out["data"] = {"pilots": pilots}
        if not pilots:
            out["answer"] = ("No pilot has been proposed yet. A pilot starts from a Product & Supplier Intelligence product that a category manager approved as a business opportunity: the planner proposes stores and stock "
                             "from comparable products. I do not pick stores for a product that has no estimate.")
        else:
            out["answer"] = "Pilot proposals: " + "; ".join(
                f"{p['product_name']} ({p['status'].replace('_', ' ').lower()}): " + (", ".join(s['location_id'] for s in p["stores"][:6]) if p["stores"] else f"no estimate ({p['estimate'].get('status', '').replace('_', ' ').lower()})") for p in pilots[:5]) + "."
        out["evidence"] = [{"label": "Pilots", "link": link("pilots")}]
        out["limitations"] = ["A pilot estimate is a LOW-confidence range taken from comparable products; it is not a forecast."]
        return out

    # the remaining intents work on a plan
    if intent == "FORECAST":
        data, err = await _call(registry, ctx, "get_weekly_forecast", {"source": source, "limit": 12, **{k: v for k, v in scope.items() if k in ("sport", "category", "region", "store")}})
        if err:
            return await fail(err)
        out["data"] = {"run": data["run"], "total": data["total"]}
        if not data["items"]:
            out["answer"] = "No forecast matches that scope."
            return out
        first = data["run"]["first_week"]
        rows = [f"{r['store_name']} {r['product_name']}: {_fmt(r['weeks'][0]['forecast'], 1)} (range {_fmt(r['weeks'][0]['lo'], 1)} to {_fmt(r['weeks'][0]['hi'], 1)}, {r['confidence'].lower()} confidence)" for r in data["items"] if r["weeks"]]
        low = sum(1 for r in data["items"] if r["confidence"] in ("LOW", "NONE"))
        out["answer"] = f"Forecast units for the week of {first} ({data['total']} product-store series match; first {len(rows)} shown): " + "; ".join(rows[:8]) + f".{_banner(synthetic)}" + (f" {low} of them are low-confidence." if low else "")
        out["evidence"] = [{"label": "Forecast", "link": link("forecast", **{k: scope[k] for k in ("sport", "category", "region") if k in scope})}]
        out["limitations"] = ["Weekly units per store; a forecast is a model estimate with a range, not a promise."]
        return out

    if intent == "OVERVIEW":
        data, err = await _call(registry, ctx, "get_planning_overview", {"source": source, **scope})
        if err:
            return await fail(err)
        out["data"] = data
        m, q_ = data["metrics"], data["queues"]
        if not data["plan"]:
            out["answer"] = data.get("message") or "There is no plan yet."
        else:
            out["answer"] = (f"Plan '{data['plan']['name']}' ({data['plan']['status'].lower()}): model forecast {_fmt(m.get('model_forecast_units'))} units, plan forecast {_fmt(m.get('plan_forecast_units'))}, target {_fmt(m.get('target_units'))}, "
                             f"actual {_fmt(m.get('actual_units'))} (closed weeks only; target attainment {_fmt(m.get('target_attainment_pct'), 1)}%). {q_.get('plans_awaiting_approval', 0)} plan(s) await approval; "
                             f"{_fmt(q_.get('stores_at_stockout_risk'))} store(s) at stock-out risk; {_fmt(q_.get('products_to_replenish'))} product(s) to replenish.{_banner(synthetic)}")
            out["evidence"] = [{"label": "Dashboard", "link": link("dashboard", plan=data["plan"]["id"])}]
        return out

    if intent in ("COMPARE", "TARGETS", "WHY"):
        grain = "store" if (scope.get("store") or re.search(r"\bstores?\b", question.lower())) and intent != "COMPARE" else "region" if re.search(r"\bregion", question.lower()) else "sku" if intent == "TARGETS" else "category"
        data, err = await _call(registry, ctx, "get_plan_vs_actual", {"source": source, "grain": grain, **scope})
        if err:
            return await fail(err)
        out["data"] = data
        rows = data["rows"]
        closed = [r for r in rows if r["with_actual"]]
        if not closed:
            out["answer"] = "No week in this plan has closed yet, so there is no actual to compare with the forecast and target. " + (f"Data runs through {data['data_through']}." if data["data_through"] else "")
            out["evidence"] = [{"label": "Plan performance", "link": link("performance", plan=data["plan"]["id"])}]
            return out
        if intent == "TARGETS":
            miss = sorted([r for r in closed if r["target_attainment_pct"] is not None and r["target_attainment_pct"] < 90], key=lambda r: r["target_attainment_pct"])[:8]
            out["answer"] = ("Below 90% of target over the closed weeks: " + "; ".join(f"{r['gkey']} {r['target_attainment_pct']:.0f}% ({_fmt(r['actual'])} of {_fmt(r['t_a'])} units)" for r in miss) + "."
                             if miss else "Nothing with a target is below 90% attainment over the closed weeks.")
        else:
            out["answer"] = "Over the closed weeks: " + "; ".join(
                f"{r['gkey']}: actual {_fmt(r['actual'])}, forecast {_fmt(r['forecast'])}, target {_fmt(r['target'])}" + (f", {r['forecast_variance']:+,.0f} vs forecast" if r['forecast_variance'] is not None else "") +
                (f", {r['target_attainment_pct']:.0f}% of target" if r['target_attainment_pct'] is not None else "") for r in closed[:8]) + f". Basis: {data['basis'].lower().replace('_', ' ')} forecast.{_banner(synthetic)}"
            if intent == "WHY":
                out["limitations"].append("To see why one product and week differed, open the variance investigation: it lists the recorded facts (stock-outs, returns, corrections) and labels every hypothesis as one.")
        out["evidence"] = [{"label": "Plan performance", "link": link("performance", plan=data["plan"]["id"])}]
        out["limitations"].append(data["notice"])
        return out

    if intent in ("RISK", "ALLOCATE", "TRANSFER"):
        args: dict[str, Any] = {"source": source, "only_actions": intent != "RISK", "limit": 15, **{k: v for k, v in scope.items() if k in ("sport", "category", "region", "store")}}
        if intent == "RISK":
            args["risk"] = "HIGH"
        data, err = await _call(registry, ctx, "get_inventory_recommendations", args)
        if err:
            return await fail(err)
        out["data"] = {"summary": data["summary"], "items": data["items"], "transfers": data["transfers"]}
        summ = data["summary"]
        if not summ.get("recs"):
            out["answer"] = "No allocation has been generated for the plan yet. Generate it on the Inventory planning screen; I do not estimate stock needs from memory."
            return out
        if intent == "TRANSFER":
            tr = data["transfers"][:6]
            out["answer"] = ("Recommended transfers (drafts): " + "; ".join(f"{t['qty']} x {t['product_name']} from {t['from_name']} to {t['to_name']} ({t['lead_days']} days)" for t in tr) + "."
                             if tr else "No transfer is recommended: no store has surplus stock that another store needs soon enough.")
        elif intent == "RISK":
            hi = data["items"][:8]
            out["answer"] = (f"{summ['high_risk']} product-store position(s) are at high risk of running out. Highest first: " + "; ".join(
                f"{r['store_name']} {r['product_name']} ({_fmt(r['coverage_days'], 0)} days of cover, stock {r['on_hand']}+{r['incoming']} incoming)" for r in hi) + ".") if hi else "No position is at high risk of running out."
        else:
            od = [r for r in data["items"] if r["action"] == "ORDER"][:8]
            out["answer"] = (f"{_fmt(summ['units_to_order'])} unit(s) across {_fmt(summ['products_to_order'])} product(s) need ordering. " + "; ".join(
                f"{r['store_name']} {r['product_name']}: {r['recommended_qty']} by {r['order_by']}" for r in od) + ". Supplier MOQ and case pack are applied once per product across all stores.") if od else "Nothing needs ordering."
        out["evidence"] = [{"label": "Inventory planning", "link": link("inventory")}]
        out["limitations"] = ["Recommendations are drafts. Nothing has been ordered or transferred: approving one records a decision only.", f"Stock and supplier terms are demonstration reference data{_banner(synthetic)}."]
        return out

    if intent == "SCENARIO":
        t = question.lower()
        num = NUMBER.search(t)
        kind = "LEAD_TIME" if "lead time" in t else "SAFETY_STOCK" if "safety" in t else "PROMOTION" if re.search(r"promotion|discount|% off", t) else "GROWTH_TARGET"
        if not scope.get("sport") and not scope.get("category"):
            out["answer"] = "Name a sport or category for the scenario (for example 'cricket bats'): it is evaluated product by product for at most 150 products."
            return out
        args = {"source": source, "kind": kind, **{k: v for k, v in scope.items() if k in ("sport", "category", "region", "store")}}
        value = float(num.group(1)) if num else None
        if kind == "LEAD_TIME":
            args["delta_days"] = int(value or 7) * (-1 if re.search(r"decreas|reduc|shorter", t) else 1)
        elif kind == "SAFETY_STOCK":
            args["service_level"] = (value / 100 if value and value > 1 else value) or 0.99
        elif kind == "PROMOTION":
            args.update({"discount_pct": value or 10, "weeks": 2})
        else:
            args["growth_pct"] = value or 10
        data, err = await _call(registry, ctx, "simulate_planning_scenario", args)
        if err:
            return await fail(err)
        out["data"] = {"summary": data["summary"], "kind": kind, "limits": data["limits"]}
        s = data["summary"]
        out["answer"] = (f"Simulated {kind.replace('_', ' ').lower()} for {data['scope'] and ', '.join(str(v) for v in data['scope'].values())}: order quantity {_fmt(s['baseline']['order_units'])} to {_fmt(s['scenario']['order_units'])} units "
                         f"({s['delta']['order_units']:+,.0f}), stores at high risk {_fmt(s['baseline']['stores_high_risk'])} to {_fmt(s['scenario']['stores_high_risk'])}"
                         + (f", order cost {_fmt(s['baseline']['order_cost'])} to {_fmt(s['scenario']['order_cost'])}" if s['scenario']['order_cost'] is not None else "") + ". This is a simulation: nothing was saved or changed.")
        out["evidence"] = [{"label": "Scenarios", "link": link("scenarios")}]
        out["limitations"] = data["limits"]
        return out
    return out
