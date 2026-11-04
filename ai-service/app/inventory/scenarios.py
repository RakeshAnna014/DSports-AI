"""Promotion / clearance what-if engine. Deterministic arithmetic over persisted forecasts and ESTIMATED elasticities. NO LLM.

Demand response: promo_demand = baseline_demand x (1 - d) ** (-e), where e is the category's price elasticity of demand (a positive
number here, the magnitude of the negative elasticity), estimated by regressing log(actual / baseline) on log(1 - d) over historical
promotion days. A share of the uplift (`pull_forward`) is borrowed from the 14 days after the promotion; it is measured the same way
and reported. Without enough promotion history a clearly flagged POC prior is used (basis = POC_ASSUMPTION) and the result says so.

For each discount the engine reports units, revenue, margin, ending stock, stock-out risk and clearance days, with a range from the
elasticity's own uncertainty. The recommendation maximises `net_benefit` subject to a margin floor; both are stated in the output:

  net_benefit(d) = margin(d) - margin(0) + carrying_cost_avoided(d)
  carrying_cost_avoided(d) = extra_units_sold(d) x unit_cost x CARRY_RATE x min(baseline_clearance_days, 365) / 365
  writedown_avoided(d)     = min(extra_units_sold(d), excess_units) x unit_cost x writedown_rate(age)       [aged excess stock only]
  writedown_rate(age)      = 0 below 60 days, then 15% rising linearly to 40% at 240+ days (POC assumption, reported with results)

  The write-down term is what makes clearing genuinely aged excess stock worthwhile: units that would otherwise sit unsold keep
  ageing and eventually need a deeper markdown or a write-off.

It recommends only. Prices are owned by the DSports pricing engine; nothing here writes anywhere.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date

import numpy as np

CARRY_RATE = 0.25            # annual cost of holding stock as a share of its purchase cost (POC assumption, reported with results)
MIN_MARGIN_PCT = 10.0        # a discount that leaves less unit margin than this is never recommended
DEFAULT_DURATION = 14
PRIOR_ELASTICITY = (1.5, 1.0, 2.2)   # (e, low, high) POC prior, used only without promotion history
PRIOR_PULL_FORWARD = 0.10
MIN_PROMO_DAYS = 40
MAX_DISCOUNT = 70.0
WRITEDOWN_FREE_DAYS, WRITEDOWN_MIN, WRITEDOWN_MAX, WRITEDOWN_FULL_DAYS = 60, 0.15, 0.40, 240


def writedown_rate(age_days: int) -> float:
    if age_days < WRITEDOWN_FREE_DAYS:
        return 0.0
    frac = min(1.0, (age_days - WRITEDOWN_FREE_DAYS) / (WRITEDOWN_FULL_DAYS - WRITEDOWN_FREE_DAYS))
    return WRITEDOWN_MIN + (WRITEDOWN_MAX - WRITEDOWN_MIN) * frac


@dataclass(frozen=True)
class Elasticity:
    category: str
    e: float                  # positive magnitude
    lo: float
    hi: float
    pull_forward: float
    n_promo_days: int
    basis: str                # HISTORICAL | POC_ASSUMPTION


def estimate_elasticity(category: str, per_product: list[dict], rng_seed: int = 7) -> Elasticity:
    """per_product: {raw, fitted, discount, unusable, event (bool array)} over the same days. Bootstrap over products for the range."""
    xs: list[list[tuple[float, float]]] = []
    pulls: list[float] = []
    total_days = 0
    for p in per_product:
        promo = (p["discount"] > 0) & ~p["event"] & ~p["unusable"] & ~np.isnan(p["raw"]) & (p["fitted"] > 0.3)
        if promo.sum() < 5:
            continue
        pts = []
        for t in np.flatnonzero(promo):
            d = p["discount"][t] / 100.0
            ratio = max(p["raw"][t], 0.5) / p["fitted"][t]
            pts.append((math.log(1.0 - d), math.log(ratio)))
        xs.append(pts)
        total_days += len(pts)
        # pull-forward: demand missing in the 7 days after each promotion relative to the extra demand during it
        disc = p["discount"]
        for t in range(1, len(disc) - 8):
            if disc[t - 1] > 0 and disc[t] == 0:
                post = slice(t, t + 7)
                during = np.flatnonzero(disc[max(0, t - 14):t] > 0) + max(0, t - 14)
                if len(during) < 5 or p["unusable"][post].any() or p["event"][post].any() or np.isnan(p["raw"][post]).any():
                    continue
                extra = float((p["raw"][during] - p["fitted"][during]).sum())
                lost = float((p["fitted"][post] - p["raw"][post]).sum())
                if extra > 1:
                    pulls.append(min(max(lost / extra, 0.0), 0.6))
    if total_days < MIN_PROMO_DAYS or len(xs) < 2:
        return Elasticity(category, PRIOR_ELASTICITY[0], PRIOR_ELASTICITY[1], PRIOR_ELASTICITY[2], PRIOR_PULL_FORWARD, total_days, "POC_ASSUMPTION")

    def slope(sample: list[list[tuple[float, float]]]) -> float:
        pts = np.array([pt for s in sample for pt in s])
        x, y = pts[:, 0], pts[:, 1]
        return float((x * y).sum() / max((x * x).sum(), 1e-9))     # regression through the origin: log ratio = -e * log(1-d)

    e = -slope(xs)
    rng = np.random.default_rng(rng_seed)
    boots = [-slope([xs[i] for i in rng.integers(0, len(xs), len(xs))]) for _ in range(200)]
    return Elasticity(category, round(float(e), 3), round(float(np.quantile(boots, 0.10)), 3), round(float(np.quantile(boots, 0.90)), 3),
                      round(float(np.median(pulls)) if pulls else PRIOR_PULL_FORWARD, 3), total_days, "HISTORICAL")


@dataclass(frozen=True)
class Item:
    product_id: str
    name: str
    price: float
    cost: float | None
    on_hand: int
    arrivals: tuple[tuple[int, int], ...]
    daily: tuple[float, ...]            # baseline forecast, day 1.. (as_of onward)
    elasticity: Elasticity
    age_days: int = 0
    excess_units: int = 0


def _units(item: Item, d_pct: float, days: int, e: float) -> tuple[float, float]:
    """(baseline demand, promotional demand) over the promotion window, before the stock cap."""
    base = float(sum(item.daily[:days]))
    return base, base * (1.0 - d_pct / 100.0) ** (-e)


def run_item(item: Item, d_pct: float, days: int = DEFAULT_DURATION) -> dict:
    available = item.on_hand + sum(q for k, q in item.arrivals if k < days)
    base, promo = _units(item, d_pct, days, item.elasticity.e)
    _, promo_lo = _units(item, d_pct, days, item.elasticity.lo)
    _, promo_hi = _units(item, d_pct, days, item.elasticity.hi)
    sold = min(promo, available)
    unit_price = item.price * (1.0 - d_pct / 100.0)
    margin_unit = None if item.cost is None else unit_price - item.cost
    rate = promo / days if days else 0.0
    ratio = promo / available if available > 0 else math.inf
    return {
        "product_id": item.product_id, "name": item.name, "discount_pct": d_pct, "days": days,
        "baseline_demand": round(base, 1), "expected_demand": round(promo, 1),
        "units_sold": round(sold, 1), "units_low": round(min(promo_lo, available), 1), "units_high": round(min(promo_hi, available), 1),
        "unit_price": round(unit_price, 2), "revenue": round(sold * unit_price, 2),
        "margin": None if margin_unit is None else round(sold * margin_unit, 2),
        "unit_margin_pct": None if margin_unit is None else round(100.0 * margin_unit / unit_price, 1) if unit_price > 0 else None,
        "available": available, "ending_stock": round(max(available - sold, 0.0), 1),
        "clearance_days": None if rate <= 0 else round(item.on_hand / rate, 1),
        "stockout_risk": "HIGH" if ratio >= 1.0 else "MEDIUM" if ratio >= 0.8 else "LOW",
        "borrowed_from_future": round(item.elasticity.pull_forward * max(sold - base, 0.0), 1),
    }


def validate_inputs(items: list[Item], discounts: list[float], days: int, min_margin_pct: float) -> None:
    """Impossible inputs fail loudly (ValueError) instead of producing a number."""
    if not items:
        raise ValueError("no products to evaluate")
    if not discounts or any(not isinstance(d, (int, float)) or d != d or d < 0 or d > MAX_DISCOUNT for d in discounts):
        raise ValueError(f"discounts must be numbers between 0 and {MAX_DISCOUNT:g} percent")
    if not isinstance(days, int) or days < 1 or days > 365:
        raise ValueError("the promotion duration must be between 1 and 365 days")
    if min_margin_pct < 0 or min_margin_pct >= 100:
        raise ValueError("the margin floor must be between 0 and 100 percent")
    for i in items:
        if i.price <= 0 or (i.cost is not None and i.cost < 0) or i.on_hand < 0 or i.elasticity.e <= 0 or any(d < 0 or d != d for d in i.daily):
            raise ValueError(f"impossible inputs for {i.name}: price, cost, stock, elasticity and demand must be positive and non-negative")


def evaluate(items: list[Item], discounts: list[float], days: int = DEFAULT_DURATION, min_margin_pct: float = MIN_MARGIN_PCT) -> dict:
    """All items under every discount, aggregated; plus the recommended uniform discount and the best discount per product."""
    validate_inputs(items, discounts, days, min_margin_pct)
    discounts = sorted({0.0, *(float(d) for d in discounts)})        # the baseline is always part of a comparison
    results: dict[float, list[dict]] = {d: [run_item(i, d, days) for i in items] for d in discounts}
    base_rows = results.get(0.0) or [run_item(i, 0.0, days) for i in items]
    by_item = {i.product_id: i for i in items}

    def carrying(row: dict, base_row: dict) -> float:
        item = by_item[row["product_id"]]
        if item.cost is None:
            return 0.0
        extra = max(row["units_sold"] - base_row["units_sold"], 0.0)
        clear = base_row["clearance_days"] if base_row["clearance_days"] is not None else 365.0
        carry = extra * item.cost * CARRY_RATE * min(clear, 365.0) / 365.0
        return carry + min(extra, float(item.excess_units)) * item.cost * writedown_rate(item.age_days)

    summary = []
    for d in discounts:
        rows = results[d]
        net = 0.0
        for r, b in zip(rows, base_rows, strict=True):
            net += ((r["margin"] or 0.0) - (b["margin"] or 0.0)) + carrying(r, b)
        units = sum(r["units_sold"] for r in rows)
        base_units = sum(b["units_sold"] for b in base_rows)
        costs = {i.product_id: (i.cost or 0.0) for i in items}
        summary.append({
            "discount_pct": d, "units_sold": round(units, 1), "units_low": round(sum(r["units_low"] for r in rows), 1),
            "units_high": round(sum(r["units_high"] for r in rows), 1),
            "revenue": round(sum(r["revenue"] for r in rows), 2), "margin": round(sum((r["margin"] or 0.0) for r in rows), 2),
            "ending_stock": round(sum(r["ending_stock"] for r in rows), 1),
            "inventory_reduction_units": round(units - base_units, 1),
            "inventory_reduction_value": round(sum(max(r["units_sold"] - b["units_sold"], 0.0) * costs[r["product_id"]] for r, b in zip(rows, base_rows, strict=True)), 2),
            "products_at_stockout_risk": sum(r["stockout_risk"] == "HIGH" for r in rows),
            "min_unit_margin_pct": min((r["unit_margin_pct"] for r in rows if r["unit_margin_pct"] is not None), default=None),
            "net_benefit": round(net, 2), "borrowed_from_future": round(sum(r["borrowed_from_future"] for r in rows), 1),
        })
    eligible = [s for s in summary if s["discount_pct"] > 0 and (s["min_unit_margin_pct"] is None or s["min_unit_margin_pct"] >= min_margin_pct)]
    best = max(eligible, key=lambda s: s["net_benefit"]) if eligible and max(s["net_benefit"] for s in eligible) > 0 else None
    per_product = []
    for idx, item in enumerate(items):
        opts = []
        for d in discounts:
            if d <= 0:
                continue
            r, b = results[d][idx], base_rows[idx]
            if r["unit_margin_pct"] is not None and r["unit_margin_pct"] < min_margin_pct:
                continue
            opts.append((((r["margin"] or 0.0) - (b["margin"] or 0.0)) + carrying(r, b), d, r))
        top = max(opts, key=lambda o: o[0]) if opts else None
        per_product.append({"product_id": item.product_id, "name": item.name,
                            "best_discount_pct": top[1] if top and top[0] > 0 else None,
                            "net_benefit": round(top[0], 2) if top else None,
                            "units_sold": top[2]["units_sold"] if top else None})
    selective = None
    positive = [d for d in discounts if d > 0]
    if len(positive) >= 1:
        picks = {p["product_id"]: (p["best_discount_pct"] or 0.0) for p in per_product}
        rows = [results[picks[i.product_id]][idx] for idx, i in enumerate(items)]
        base_units = sum(b["units_sold"] for b in base_rows)
        costs = {i.product_id: (i.cost or 0.0) for i in items}
        net = 0.0
        for idx, item in enumerate(items):
            r, b = rows[idx], base_rows[idx]
            if picks[item.product_id] > 0:
                net += ((r["margin"] or 0.0) - (b["margin"] or 0.0)) + carrying(r, b)
        selective = {
            "discounted_products": sum(1 for v in picks.values() if v > 0), "products": len(items),
            "units_sold": round(sum(r["units_sold"] for r in rows), 1), "revenue": round(sum(r["revenue"] for r in rows), 2),
            "margin": round(sum((r["margin"] or 0.0) for r in rows), 2), "ending_stock": round(sum(r["ending_stock"] for r in rows), 1),
            "inventory_reduction_units": round(sum(r["units_sold"] for r in rows) - base_units, 1),
            "inventory_reduction_value": round(sum(max(r["units_sold"] - b["units_sold"], 0.0) * costs[r["product_id"]] for r, b in zip(rows, base_rows, strict=True)), 2),
            "net_benefit": round(net, 2),
            "discount_by_product": [{"name": i.name, "discount_pct": picks[i.product_id]} for i in items if picks[i.product_id] > 0]}
    return {"duration_days": days, "min_margin_pct": min_margin_pct, "carry_rate": CARRY_RATE, "scenarios": summary, "selective": selective,
            "recommended_discount_pct": best["discount_pct"] if best else None, "per_product_best": per_product,
            "items": {str(d): results[d] for d in discounts},
            "elasticity": [{"category": c.category, "elasticity": -c.e, "range": [-c.hi, -c.lo], "pull_forward": c.pull_forward,
                            "basis": c.basis, "n_promo_days": c.n_promo_days}
                           for c in {i.elasticity.category: i.elasticity for i in items}.values()],
            "assumptions": ["Demand response (1 - d) ** (-e), where e is the category's price elasticity of demand, estimated from historical promotion days"
                            + (" (a POC prior where history is insufficient)" if any(i.elasticity.basis != "HISTORICAL" for i in items) else ""),
                            f"Holding cost {int(CARRY_RATE * 100)}% of purchase cost per year",
                            f"Write-down risk on aged excess stock: {int(WRITEDOWN_MIN * 100)}% of cost from {WRITEDOWN_FREE_DAYS} days, rising to {int(WRITEDOWN_MAX * 100)}% at {WRITEDOWN_FULL_DAYS}+ days (POC assumption)", f"Margin floor {min_margin_pct:g}% of the discounted price",
                            "Purchase cost is POC reference data, not real DSports cost data"]}
