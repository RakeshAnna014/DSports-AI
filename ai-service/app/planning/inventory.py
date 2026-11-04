"""From an approved weekly plan to stock requirements, replenishment and store-to-store transfers. Pure functions, no I/O, no LLM.

It REUSES the Inventory AI's deterministic assessment (`app.inventory.planning.assess`: projected cover, probability of running out before a new order lands, safety stock for a 95% service
level, reorder quantity, MOQ and case-pack rounding). Nothing of that logic is copied here; this module only
  * turns weekly store forecasts into the daily demand array `assess` expects,
  * assesses each store's position separately (sellable stock = on hand - reserved; incoming = open orders and transfers that have NOT yet reached the shelf, so nothing is counted twice),
  * decides transfers before purchases (donor stores keep their own target level; a transfer must arrive sooner than new supply or before the receiver would run out),
  * applies the supplier's MOQ and case pack ONCE per product across all stores (a store never gets an order of its own), putting any MOQ top-up on the neediest stores.
Recommendations are DRAFTS. Nothing here orders, transfers, prices or changes stock.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any, Sequence

import numpy as np

from app.inventory.planning import (HORIZON, OVERSTOCK_MEDIUM_DAYS, Assessment, Position, Reference, assess, round_order)

Z80 = 1.2816
HIGH_RISK = ("HIGH", "MEDIUM")


@dataclass
class StoreInput:
    location_id: str
    region: str
    on_hand: int
    reserved: int = 0
    arrivals: tuple[tuple[int, int], ...] = ()                     # (days from as_of, qty): open purchase orders and transfers still on the way
    weekly: Sequence[float] = ()                                  # approved (or model) forecast per plan week
    weekly_lo: Sequence[float] | None = None
    weekly_hi: Sequence[float] | None = None
    confidence: str | None = None
    open_from: date | None = None

    @property
    def sellable(self) -> int:
        return max(0, int(self.on_hand) - int(self.reserved))


def daily_from_weekly(as_of: date, week_starts: Sequence[date], weekly: Sequence[float], days: int = HORIZON) -> np.ndarray:
    """Spread each week's forecast evenly over its 7 days and return the days from `as_of` onward (day 1 = as_of). A week that started before `as_of` contributes only its remaining days."""
    per_day: dict[date, float] = {}
    for w, v in zip(week_starts, weekly):
        for k in range(7):
            per_day[w + timedelta(days=k)] = float(v) / 7.0
    tail = float(weekly[-1]) / 7.0 if len(weekly) else 0.0           # days after the last planned week continue at its rate (the plan is 13 weeks, the assessment looks 90 days ahead)
    last = max(per_day) if per_day else None
    return np.array([per_day.get(as_of + timedelta(days=i), tail if last is not None and as_of + timedelta(days=i) > last else 0.0) for i in range(days)])


def sigma_daily(week_starts: Sequence[date], lo: Sequence[float] | None, hi: Sequence[float] | None) -> float | None:
    """The daily standard deviation implied by the forecast's 80% interval (width / 2.56, per week, then / sqrt(7)); None when there is no interval (assess then uses its own default)."""
    if lo is None or hi is None or not len(lo):
        return None
    w = np.array([(h - l) / (2 * Z80) for l, h in zip(lo, hi)], dtype=float)
    return float(np.sqrt((w ** 2).mean()) / math.sqrt(7)) if w.size else None


def assess_store(as_of: date, week_starts: Sequence[date], s: StoreInput, ref: Reference) -> Assessment:
    daily = daily_from_weekly(as_of, week_starts, s.weekly)
    if s.open_from and s.open_from > as_of:                         # a store that has not opened sells nothing until it does
        daily = np.array([d if as_of + timedelta(days=i) >= s.open_from else 0.0 for i, d in enumerate(daily)])
    pos = Position(on_hand=s.sellable, arrivals=tuple(s.arrivals), oldest_age_days=0, avg_age_days=0.0)
    return assess(as_of, pos, ref, daily if len(s.weekly) else None, sigma_daily(week_starts, s.weekly_lo, s.weekly_hi))


@dataclass
class StoreRec:
    location_id: str
    region: str
    action: str                                  # ORDER | EXPEDITE_INCOMING | TRANSFER_IN | NONE | REVIEW
    risk_level: str | None
    required_stock: int | None
    sellable: int
    reserved: int
    incoming: int
    shortage: int
    surplus: int
    recommended_qty: int
    transfer_in_qty: int
    transfer_out_qty: int
    order_by: date | None
    arrival_date: date | None
    safety_stock: int | None
    coverage_days: float | None
    stockout_date: date | None
    confidence: str | None
    reason: str
    status: str
    detail: dict[str, Any] = field(default_factory=dict)


@dataclass
class TransferRec:
    from_location: str
    to_location: str
    qty: int
    lead_days: int
    arrival_date: date
    reason: str
    avoids_order_qty: int
    detail: dict[str, Any] = field(default_factory=dict)


def plan_product(as_of: date, week_starts: Sequence[date], stores: Sequence[StoreInput], ref: Reference, *, transfer_lead_days: int = 3, cross_region_days: int = 6,
                 transfer_min_qty: int = 2, transfer_unit_cost: float | None = None) -> tuple[list[StoreRec], list[TransferRec], dict[str, Any]]:
    """Recommendations for ONE product across its stores."""
    assessed = {s.location_id: assess_store(as_of, week_starts, s, ref) for s in stores}
    by_id = {s.location_id: s for s in stores}
    need = {i: int(a.reorder_qty or 0) for i, a in assessed.items()}
    target = {i: int(a.detail.get("target_level", 0) or 0) for i, a in assessed.items()}
    # --- 1. transfers: donors are over-stocked, LOW risk and keep their own target level; receivers are at risk and short
    donors = []
    for i, a in assessed.items():
        s = by_id[i]
        if a.status == "OK" and (a.overstock_level in ("MEDIUM", "HIGH") or (a.coverage_days or 0) >= OVERSTOCK_MEDIUM_DAYS) and a.risk_level == "LOW" and a.excess_units:
            give = min(int(a.excess_units), max(0, s.sellable - target[i]))
            if give >= transfer_min_qty:
                donors.append([i, give])
    receivers = sorted([i for i, a in assessed.items() if a.status == "OK" and need[i] > 0 and a.risk_level in HIGH_RISK], key=lambda i: (assessed[i].risk_level != "HIGH", -need[i]))
    transfers: list[TransferRec] = []
    in_qty = {i: 0 for i in by_id}
    out_qty = {i: 0 for i in by_id}
    lt = ref.lead_time_days
    for r in receivers:
        a_r = assessed[r]
        for d in sorted(donors, key=lambda d: (by_id[d[0]].region != by_id[r].region, -d[1])):
            if need[r] - in_qty[r] <= 0:
                break
            if d[1] < transfer_min_qty:
                continue
            same = by_id[d[0]].region == by_id[r].region
            lead = transfer_lead_days if same else cross_region_days
            arrival = as_of + timedelta(days=lead)
            sooner_than_supply = lt is None or lead < lt
            before_stockout = a_r.stockout_date is None or arrival <= a_r.stockout_date
            if not (sooner_than_supply or before_stockout):
                continue                                              # a transfer that is slower than the supplier AND lands after the stock-out helps nobody
            qty = min(d[1], need[r] - in_qty[r])
            if qty < transfer_min_qty:
                continue
            d[1] -= qty
            in_qty[r] += qty
            out_qty[d[0]] += qty
            why = [f"{by_id[d[0]].location_id} holds {assessed[d[0]].coverage_days or 0:.0f} days of cover and keeps its own target level after sending {qty}.",
                   f"{r} {'would run out on ' + a_r.stockout_date.isoformat() if a_r.stockout_date else 'is at ' + str(a_r.risk_level) + ' risk'}; the transfer arrives {arrival.isoformat()}."]
            if lt is not None:
                why.append(f"New supply would take {lt} days against {lead} for a transfer" + (": the transfer is sooner." if lead < lt else ": the transfer is not sooner but lands before the stock-out."))
            if not same:
                why.append("Cross-region transfer: it needs a longer lead time and is shown only because no same-region donor could cover the need.")
            transfers.append(TransferRec(d[0], r, int(qty), lead, arrival, " ".join(why), int(qty),
                                         {"sooner_than_supplier": bool(sooner_than_supply), "arrives_before_stockout": bool(before_stockout), "same_region": bool(same),
                                          "unit_cost": transfer_unit_cost, "cost_claimed": transfer_unit_cost is not None,
                                          "receiver_risk": a_r.risk_level, "donor_coverage_days": assessed[d[0]].coverage_days}))
    # --- 2. purchases: what is still needed after transfers, ordered ONCE per product so MOQ and case pack apply to the whole order
    remaining = {i: max(0, need[i] - in_qty[i]) for i in by_id}
    total = sum(remaining.values())
    order_total = round_order(total, ref.moq, ref.case_pack) if total > 0 else 0
    top_up = order_total - total
    buy = dict(remaining)
    if top_up > 0:
        ranked = sorted([i for i in by_id if remaining[i] > 0], key=lambda i: (assessed[i].risk_level != "HIGH", -remaining[i], i))
        for k in range(top_up):
            buy[ranked[k % len(ranked)]] += 1
    recs: list[StoreRec] = []
    for i, s in by_id.items():
        a = assessed[i]
        action = "NONE"
        if a.status != "OK":
            action = "REVIEW"
        elif buy[i] > 0:
            action = "ORDER"
        elif in_qty[i] > 0:
            action = "TRANSFER_IN"
        elif (a.detail or {}).get("action") == "EXPEDITE_INCOMING":
            action = "EXPEDITE_INCOMING"
        shortage = max(0, target[i] - (s.sellable + sum(q for _, q in s.arrivals))) if a.status == "OK" else 0
        reasons = []
        if a.status == "INSUFFICIENT_DATA":
            reasons.append("No usable forecast for this store: insufficient data. Plan by hand or pilot it.")
        elif a.status == "MISSING_LEAD_TIME":
            reasons.append("The supplier lead time is not recorded: stock risk is shown from cover only and no order quantity is proposed.")
        else:
            reasons.append(f"Target stock {target[i]} = demand over lead time plus review cycle plus {a.safety_stock} safety stock (95% service level); position {s.sellable} on hand + {sum(q for _, q in s.arrivals)} incoming.")
            if in_qty[i]:
                reasons.append(f"{in_qty[i]} unit(s) can be moved in from another store, so the purchase is reduced.")
            if buy[i] and top_up > 0 and buy[i] > remaining[i]:
                reasons.append(f"Includes {buy[i] - remaining[i]} unit(s) of the supplier's minimum-order / case-pack rounding for the whole order of {order_total}.")
            if a.detail.get("arrives_after_stockout"):
                reasons.append("A new order would arrive after the projected stock-out: stock will run out first.")
            if out_qty[i]:
                reasons.append(f"{out_qty[i]} unit(s) are surplus and can go to a store that needs them.")
        recs.append(StoreRec(i, s.region, action, a.risk_level, target[i] if a.status != "INSUFFICIENT_DATA" else None, s.sellable, s.reserved, sum(q for _, q in s.arrivals), shortage, int(a.excess_units or 0),
                             int(buy[i]), int(in_qty[i]), int(out_qty[i]), a.order_by if buy[i] else None, a.arrival_date if buy[i] else None, a.safety_stock, a.coverage_days, a.stockout_date, s.confidence,
                             " ".join(reasons), "DRAFT", {"p_stockout_lead_time": a.p_stockout_lt, "lead_time_days": a.lead_time_days, "forecast_30": a.forecast_30, "forecast_90": a.forecast_90,
                                                          "demand_during_lead_time": a.detail.get("demand_during_lead_time"), "reorder_point": a.reorder_point, "status": a.status}))
    summary = {"total_need": total, "order_total": order_total, "moq_top_up": top_up, "transferred": sum(t.qty for t in transfers), "lead_time_days": lt, "moq": ref.moq, "case_pack": ref.case_pack}
    return recs, transfers, summary
