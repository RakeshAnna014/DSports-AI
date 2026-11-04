"""Planning scenarios: deterministic what-ifs over the stored weekly forecast, kept SEPARATE from every plan until an authorised person adopts one. No LLM.

Each scenario re-runs the same store-level stock assessment (`app.planning.inventory.plan_product`, which reuses the Inventory AI) on a changed input and reports baseline against scenario:
demand, required order quantity, stores at risk, estimated revenue and margin (only where a price and a cost exist), the budget the order implies, and the limits of the estimate.

  GROWTH_TARGET   target demand = forecast x (1 + g). A TARGET is not a forecast: DSports has no validated model of how a target changes demand, so the scenario answers only "what stock
                  would be needed IF that demand materialised". It does not claim the target will be reached.
  PROMOTION       demand in the promotion weeks x (1 - d) ** (-e) with the category's price elasticity from the Inventory AI (e, its range, and whether it is HISTORICAL or a POC_ASSUMPTION),
                  and a pull-forward share taken back from the two weeks after. Revenue and margin use the discounted price.
  LEAD_TIME       the supplier lead time changes to a new value (or by a number of days).
  SAFETY_STOCK    the safety stock is scaled to a different service level (z of the 95% level of 1.645 is replaced by the z of the requested level).
  TRANSFER        move a quantity of one product from one store to another and compare the stock risk of both.
  PILOT           handled by app.planning.pilot (a new product has no forecast to change).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace
from datetime import date
from statistics import NormalDist
from typing import Any, Sequence

import numpy as np

from app.inventory.planning import SERVICE_Z, Reference
from app.inventory.scenarios import Elasticity
from app.planning.inventory import StoreInput, plan_product

KINDS = ("GROWTH_TARGET", "PROMOTION", "LEAD_TIME", "SAFETY_STOCK", "TRANSFER")


@dataclass
class ProductCtx:
    product_id: str
    name: str
    sport: str
    category: str
    price: float | None
    cost: float | None
    ref: Reference
    stores: list[StoreInput]
    elasticity: Elasticity | None = None


class ScenarioError(ValueError):
    pass


def validate(kind: str, p: dict[str, Any]) -> None:
    if kind not in KINDS:
        raise ScenarioError(f"scenario kind must be one of {', '.join(KINDS)}")
    if kind == "GROWTH_TARGET" and not (-50 <= float(p.get("growth_pct", math.nan)) <= 200):
        raise ScenarioError("growth_pct must be between -50 and 200")
    if kind == "PROMOTION":
        d = float(p.get("discount_pct", math.nan))
        if not (0 < d <= 70):
            raise ScenarioError("discount_pct must be above 0 and at most 70")
        if not (1 <= int(p.get("weeks", 0)) <= 12):
            raise ScenarioError("weeks must be between 1 and 12")
        if int(p.get("start_week_index", 0)) < 0:
            raise ScenarioError("start_week_index cannot be negative")
    if kind == "LEAD_TIME" and p.get("lead_time_days") is None and p.get("delta_days") is None:
        raise ScenarioError("give lead_time_days or delta_days")
    if kind == "SAFETY_STOCK" and not (0.5 < float(p.get("service_level", math.nan)) < 0.9999):
        raise ScenarioError("service_level must be between 0.5 and 0.9999")
    if kind == "TRANSFER":
        for k in ("product_id", "from_location", "to_location"):
            if not p.get(k):
                raise ScenarioError(f"{k} is required")
        if p["from_location"] == p["to_location"] or int(p.get("qty", 0)) <= 0:
            raise ScenarioError("a transfer needs two different stores and a positive quantity")


def _totals(ctx: ProductCtx, as_of: date, weeks: Sequence[date], cfg: dict[str, Any], stores: list[StoreInput] | None = None, ref: Reference | None = None) -> dict[str, Any]:
    recs, transfers, summary = plan_product(as_of, weeks, stores or ctx.stores, ref or ctx.ref, **cfg)
    demand = sum(sum(s.weekly) for s in (stores or ctx.stores))
    at_risk = [r.location_id for r in recs if r.risk_level == "HIGH"]
    return {"demand_units": round(demand, 1), "order_units": summary["order_total"], "transfer_units": summary["transferred"], "stores_high_risk": len(at_risk), "high_risk_stores": at_risk[:20],
            "order_cost": None if ctx.cost is None else round(summary["order_total"] * ctx.cost, 2), "lead_time_days": summary["lead_time_days"]}


def _commercial(ctx: ProductCtx, units: float, discount_pct: float = 0.0) -> dict[str, Any]:
    if ctx.price is None:
        return {"revenue": None, "margin": None}
    price = ctx.price * (1 - discount_pct / 100.0)
    return {"revenue": round(units * price, 2), "margin": None if ctx.cost is None else round(units * (price - ctx.cost), 2)}


def _scale(stores: list[StoreInput], factor_by_week: Sequence[float]) -> list[StoreInput]:
    out = []
    for s in stores:
        n = len(s.weekly)
        f = [factor_by_week[i] if i < len(factor_by_week) else 1.0 for i in range(n)]
        out.append(replace(s, weekly=[v * k for v, k in zip(s.weekly, f)], weekly_lo=None if s.weekly_lo is None else [v * k for v, k in zip(s.weekly_lo, f)],
                           weekly_hi=None if s.weekly_hi is None else [v * k for v, k in zip(s.weekly_hi, f)]))
    return out


def evaluate_product(kind: str, p: dict[str, Any], ctx: ProductCtx, as_of: date, weeks: Sequence[date], cfg: dict[str, Any]) -> dict[str, Any]:
    base = _totals(ctx, as_of, weeks, cfg)
    base_comm = _commercial(ctx, base["demand_units"])
    out: dict[str, Any] = {"product_id": ctx.product_id, "name": ctx.name, "category": ctx.category, "baseline": {**base, **base_comm}}
    if kind == "GROWTH_TARGET":
        g = float(p["growth_pct"]) / 100.0
        scen = _totals(ctx, as_of, weeks, cfg, _scale(ctx.stores, [1 + g] * len(weeks)))
        out["scenario"] = {**scen, **_commercial(ctx, scen["demand_units"]), "note": "Target demand, not a forecast: the stock needed IF this demand materialised."}
    elif kind == "PROMOTION":
        e = ctx.elasticity
        if e is None:
            raise ScenarioError(f"No price-elasticity estimate exists for {ctx.category}: a promotion cannot be estimated")
        d, n, start = float(p["discount_pct"]), int(p["weeks"]), int(p.get("start_week_index", 0))
        uplift = (1 - d / 100.0) ** (-e.e)
        factors = [1.0] * len(weeks)
        for i in range(start, min(start + n, len(weeks))):
            factors[i] = uplift
        extra = sum(sum(s.weekly[i] for s in ctx.stores) * (uplift - 1) for i in range(start, min(start + n, len(weeks))))
        borrow = e.pull_forward * extra
        after = [i for i in range(start + n, min(start + n + 2, len(weeks)))]
        tot_after = sum(sum(s.weekly[i] for s in ctx.stores) for i in after) or 1.0
        for i in after:                                                  # the borrowed units come out of the weeks after, in proportion to their demand
            factors[i] = max(0.0, 1 - borrow / tot_after)
        scen_stores = _scale(ctx.stores, factors)
        scen = _totals(ctx, as_of, weeks, cfg, scen_stores)
        promo_units = sum(sum(s.weekly[i] for s in scen_stores) for i in range(start, min(start + n, len(weeks))))
        base_promo_units = sum(sum(s.weekly[i] for s in ctx.stores) for i in range(start, min(start + n, len(weeks))))
        comm_scen = _commercial(ctx, scen["demand_units"] - promo_units)
        comm_promo = _commercial(ctx, promo_units, d)
        revenue = None if comm_scen["revenue"] is None else round(comm_scen["revenue"] + comm_promo["revenue"], 2)
        margin = None if (comm_scen["margin"] is None or comm_promo["margin"] is None) else round(comm_scen["margin"] + comm_promo["margin"], 2)
        out["scenario"] = {**scen, "revenue": revenue, "margin": margin, "promotion_units": round(promo_units, 1), "baseline_promotion_units": round(base_promo_units, 1),
                           "uplift_multiplier": round(uplift, 3), "uplift_range": [round((1 - d / 100.0) ** (-e.lo), 3), round((1 - d / 100.0) ** (-e.hi), 3)], "elasticity": e.e,
                           "elasticity_basis": e.basis, "pull_forward_share": e.pull_forward}
    elif kind == "LEAD_TIME":
        new = p.get("lead_time_days")
        lt = int(new) if new is not None else (ctx.ref.lead_time_days or 0) + int(p["delta_days"])
        if lt < 0:
            raise ScenarioError("the lead time cannot be negative")
        scen = _totals(ctx, as_of, weeks, cfg, ref=replace(ctx.ref, lead_time_days=lt))
        out["scenario"] = {**scen, **_commercial(ctx, scen["demand_units"])}
    elif kind == "SAFETY_STOCK":
        z = NormalDist().inv_cdf(float(p["service_level"]))
        k = z / SERVICE_Z
        widened = []
        for s in ctx.stores:
            lo = None if s.weekly_lo is None else [w - (w - l) * k for w, l in zip(s.weekly, s.weekly_lo)]
            hi = None if s.weekly_hi is None else [w + (h - w) * k for w, h in zip(s.weekly, s.weekly_hi)]
            widened.append(replace(s, weekly_lo=lo, weekly_hi=hi))
        scen = _totals(ctx, as_of, weeks, cfg, widened)
        out["scenario"] = {**scen, **_commercial(ctx, scen["demand_units"]), "service_z": round(z, 3), "baseline_z": SERVICE_Z,
                           "note": "Safety stock scaled by z(service level) / 1.645, the 95% level the Inventory AI uses."}
    elif kind == "TRANSFER":
        if p["product_id"] != ctx.product_id:
            raise ScenarioError("the transfer product is not in scope")
        q = int(p["qty"])
        stores = {s.location_id: s for s in ctx.stores}
        if p["from_location"] not in stores or p["to_location"] not in stores:
            raise ScenarioError("both stores must have a stock position for this product")
        src = stores[p["from_location"]]
        if q > src.sellable:
            raise ScenarioError(f"{p['from_location']} has only {src.sellable} sellable unit(s)")
        moved = [replace(s, on_hand=s.on_hand - q) if s.location_id == p["from_location"] else replace(s, on_hand=s.on_hand + q) if s.location_id == p["to_location"] else s for s in ctx.stores]
        before = {r.location_id: r for r in plan_product(as_of, weeks, ctx.stores, ctx.ref, **cfg)[0]}
        after = {r.location_id: r for r in plan_product(as_of, weeks, moved, ctx.ref, **cfg)[0]}
        out["scenario"] = {**_totals(ctx, as_of, weeks, cfg, moved), "stores": {i: {"risk_before": before[i].risk_level, "risk_after": after[i].risk_level, "coverage_days_before": before[i].coverage_days,
                                                                                  "coverage_days_after": after[i].coverage_days} for i in (p["from_location"], p["to_location"])},
                           "note": "Arrival timing of the transfer is not modelled in this comparison; the position is shown as if it had already arrived."}
    out["delta"] = {k: (None if out["baseline"].get(k) is None or out["scenario"].get(k) is None else round(out["scenario"][k] - out["baseline"][k], 2))
                    for k in ("demand_units", "order_units", "stores_high_risk", "order_cost", "revenue", "margin")}
    return out


LIMITS = {
    "GROWTH_TARGET": ["A target is not a forecast: no model here says the target would be reached.", "Revenue and margin are the stock plan's implied demand at current prices, not a prediction."],
    "PROMOTION": ["The uplift uses the category's price elasticity from the Inventory AI; when its basis is POC_ASSUMPTION it is an assumption, not a measurement.",
                  "Cannibalisation of other products and competitor reaction are not modelled."],
    "LEAD_TIME": ["Supplier reliability and partial deliveries are not modelled."],
    "SAFETY_STOCK": ["The forecast interval is the only measure of uncertainty used."],
    "TRANSFER": ["Transfer cost and handling are not claimed unless PLANNING_TRANSFER_UNIT_COST is configured."],
}


def evaluate(kind: str, params: dict[str, Any], products: Sequence[ProductCtx], as_of: date, weeks: Sequence[date], cfg: dict[str, Any]) -> dict[str, Any]:
    validate(kind, params)
    items, skipped = [], []
    for ctx in products:
        if kind == "TRANSFER" and ctx.product_id != params["product_id"]:
            continue
        try:
            items.append(evaluate_product(kind, params, ctx, as_of, weeks, cfg))
        except ScenarioError as exc:
            if kind in ("TRANSFER",):
                raise
            skipped.append({"product_id": ctx.product_id, "name": ctx.name, "why": str(exc)})
    if not items:
        raise ScenarioError("Nothing in scope can be evaluated" + (f": {skipped[0]['why']}" if skipped else ""))

    def tot(side: str, key: str) -> float | None:
        vals = [i[side].get(key) for i in items if i[side].get(key) is not None]
        return round(float(sum(vals)), 2) if vals else None

    summary = {side: {k: tot(side, k) for k in ("demand_units", "order_units", "stores_high_risk", "order_cost", "revenue", "margin")} for side in ("baseline", "scenario")}
    summary["delta"] = {k: (None if summary["baseline"][k] is None or summary["scenario"][k] is None else round(summary["scenario"][k] - summary["baseline"][k], 2)) for k in summary["baseline"]}
    return {"kind": kind, "params": params, "summary": summary, "items": items, "skipped": skipped, "limits": LIMITS[kind],
            "status": "SIMULATION", "notice": "A simulation. It changes no plan, forecast, target, price, order or stock until an authorised person adopts it."}
