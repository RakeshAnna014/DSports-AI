"""Deterministic inventory planning: days of cover, stock-out risk, safety stock, reorder point, replenishment, overstock, aging.

Pure functions over a persisted forecast and the current position. NO LLM, no I/O. Every threshold is a named constant here and is
documented in docs/ai/phase2/04..06.

Time convention: the position is the stock at the START of `as_of`; day 1 of the forecast is `as_of` itself. A purchase order with
ETA `as_of + k` days is available from the start of day k + 1 (k = 0: today).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

SERVICE_Z = 1.645                 # 95% cycle service level
REVIEW_DAYS = 7                   # the order cycle: stock must also cover the days until the next review
HIGH_P, MEDIUM_P = 0.50, 0.20     # probability of running out before a new order could arrive
MEDIUM_BUFFER_DAYS = 7            # cover shorter than lead time + this is MEDIUM
OVERSTOCK_HIGH_DAYS, OVERSTOCK_MEDIUM_DAYS = 180, 120
SLOW_DAYS, AGED_DAYS = 120, 90
CRITICAL_AGE_DAYS, CRITICAL_COVER_DAYS = 120, 150
WATCH_COVER_DAYS, WATCH_AGE_DAYS = 75, 75
HORIZON = 90
_Z80 = 1.2816


@dataclass(frozen=True)
class Position:
    on_hand: int
    arrivals: tuple[tuple[int, int], ...] = ()         # (days from as_of, qty)
    oldest_age_days: int = 0
    avg_age_days: float = 0.0

    @property
    def incoming(self) -> int:
        return sum(q for _, q in self.arrivals)


@dataclass(frozen=True)
class Reference:
    lead_time_days: int | None
    moq: int | None = None
    case_pack: int | None = None
    cost: float | None = None
    price: float | None = None


@dataclass
class Assessment:
    status: str                                         # OK | INSUFFICIENT_DATA | MISSING_LEAD_TIME
    reason: str | None = None
    on_hand: int | None = None
    incoming: int | None = None
    lead_time_days: int | None = None
    daily_demand: float | None = None
    forecast_7: float | None = None
    forecast_30: float | None = None
    forecast_90: float | None = None
    days_of_cover: float | None = None                  # None = does not run out within the 90-day horizon
    stockout_date: date | None = None
    p_stockout_lt: float | None = None
    safety_stock: int | None = None
    reorder_point: int | None = None
    risk_level: str | None = None
    reorder_qty: int | None = None
    order_by: date | None = None
    arrival_date: date | None = None
    priority: str | None = None
    excess_units: int | None = None
    excess_value: float | None = None
    coverage_days: float | None = None
    overstock_level: str | None = None
    health_class: str | None = None
    age_days: int | None = None
    detail: dict = field(default_factory=dict)


def projected_cover(on_hand: int, arrivals: tuple[tuple[int, int], ...], daily: np.ndarray) -> tuple[float | None, list[float]]:
    """Walk the forecast day by day. Returns (days until the stock runs out | None, projected end-of-day stock per day)."""
    stock = float(on_hand)
    by_day: dict[int, int] = {}
    for k, q in arrivals:
        by_day[max(0, k)] = by_day.get(max(0, k), 0) + q
    projected: list[float] = []
    cover: float | None = None
    for i, d in enumerate(daily):
        stock += by_day.get(i, 0)
        if cover is None and d > 0 and stock - d <= 0:
            cover = i + max(stock, 0.0) / d             # fractional day on which it hits zero
        stock = max(stock - d, 0.0) if cover is None else 0.0
        projected.append(stock)
    return cover, projected


def _lt_sd(sum_lt: float, lt: int, interval: tuple[float, float] | None, sigma_daily: float) -> float:
    """Std of demand over the lead time: from the calibrated 80% interval of the horizon sum when available."""
    if interval is not None:
        return max((interval[1] - interval[0]) / (2 * _Z80), 1e-6)
    return max(sigma_daily * math.sqrt(max(lt, 1)), 1e-6)


def _normal_sf(x: float) -> float:
    return 0.5 * math.erfc(x / math.sqrt(2))


def round_order(qty: float, moq: int | None, case_pack: int | None) -> int:
    """Round up to the case pack, then lift to the minimum order quantity (kept a multiple of the case pack)."""
    q = max(0, math.ceil(qty - 1e-9))
    if q == 0:
        return 0
    if case_pack and case_pack > 1:
        q = math.ceil(q / case_pack) * case_pack
    if moq and q < moq:
        q = math.ceil(moq / (case_pack or 1)) * (case_pack or 1)
    return int(q)


def assess(as_of: date, pos: Position, ref: Reference, daily: np.ndarray | None, sigma_daily: float | None,
           interval_fn=None, review_days: int = REVIEW_DAYS, trend_pct: float | None = None) -> Assessment:
    """`daily`: forecast of baseline demand for days 1..90 (None = no forecast). `interval_fn(h, forecast_sum)` -> (lo, hi) 80% interval
    of the h-day sum, used for the probability of running out."""
    if pos.on_hand < 0 or any(q < 0 or k < 0 for k, q in pos.arrivals) or pos.oldest_age_days < 0:
        raise ValueError("impossible position: negative stock, quantity, ETA or age")
    if (ref.lead_time_days is not None and ref.lead_time_days < 0) or (ref.moq is not None and ref.moq < 0) or (ref.case_pack is not None and ref.case_pack < 0):
        raise ValueError("impossible reference data: negative lead time, MOQ or case pack")
    if daily is not None and (np.isnan(np.asarray(daily, dtype=float)).any() or (np.asarray(daily, dtype=float) < 0).any()):
        raise ValueError("a demand forecast cannot contain negative or missing values")
    if daily is None or len(daily) < 30:
        return Assessment("INSUFFICIENT_DATA", "Insufficient data to produce a reliable forecast.", on_hand=pos.on_hand, incoming=pos.incoming,
                          lead_time_days=ref.lead_time_days, age_days=pos.oldest_age_days)
    daily = np.asarray(daily, dtype=float)[:HORIZON]
    f7, f30, f90 = float(daily[:7].sum()), float(daily[:30].sum()), float(daily.sum())
    mean_daily = f90 / len(daily)
    cover, projected = projected_cover(pos.on_hand, pos.arrivals, daily)
    a = Assessment("OK", on_hand=pos.on_hand, incoming=pos.incoming, lead_time_days=ref.lead_time_days, daily_demand=round(float(daily[:30].mean()), 3),
                   forecast_7=round(f7, 1), forecast_30=round(f30, 1), forecast_90=round(f90, 1), age_days=pos.oldest_age_days,
                   days_of_cover=None if cover is None else round(cover, 1))
    if cover is not None:
        a.stockout_date = as_of + timedelta(days=int(cover))
    sd_daily = float(sigma_daily) if sigma_daily and not math.isnan(sigma_daily) else max(0.3 * mean_daily, 0.5)
    pos_total = pos.on_hand + pos.incoming

    # ------------------------------------------------------------------ overstock / health (independent of lead time)
    a.coverage_days = round(min(pos.on_hand / mean_daily, 9999.0), 1) if mean_daily > 0 else 9999.0
    a.excess_units = int(max(0, pos_total - math.ceil(f90)))
    a.excess_value = round(a.excess_units * ref.cost, 2) if ref.cost is not None else None
    a.overstock_level = ("HIGH" if a.coverage_days >= OVERSTOCK_HIGH_DAYS and a.excess_units > 0 else
                         "MEDIUM" if a.coverage_days >= OVERSTOCK_MEDIUM_DAYS and a.excess_units > 0 else "NONE")
    a.detail["demand_trend_pct"] = None if trend_pct is None else round(trend_pct, 1)
    a.health_class = health_class(a.coverage_days, pos.oldest_age_days, trend_pct)

    # ------------------------------------------------------------------ stock risk + replenishment (need a lead time)
    lt = ref.lead_time_days
    if lt is None:
        a.status, a.reason = "MISSING_LEAD_TIME", "Supplier lead time is not recorded: stock risk uses cover only and no order quantity is proposed."
        a.risk_level = "HIGH" if cover is not None and cover < 14 else "MEDIUM" if cover is not None and cover < 30 else "LOW"
        a.priority = "NONE"
        return a
    lt = int(lt)
    sum_lt = float(daily[:lt].sum()) if lt <= len(daily) else float(daily.sum())
    interval = interval_fn(lt, sum_lt) if interval_fn else None
    sd_lt = _lt_sd(sum_lt, lt, interval, sd_daily)
    a.safety_stock = int(math.ceil(SERVICE_Z * sd_lt))
    a.reorder_point = int(math.ceil(sum_lt + a.safety_stock))
    # chance of running out before a NEW order could land (day `lt`) or before any already-open order lands: the worst checkpoint wins
    worst = 0.0
    for t in sorted({lt} | {k for k, _ in pos.arrivals if 0 < k <= lt}):
        have = pos.on_hand + sum(q for k, q in pos.arrivals if k < t)
        need = float(daily[:t].sum())
        sd_t = sd_lt if t == lt else _lt_sd(need, t, interval_fn(t, need) if interval_fn else None, sd_daily)
        worst = max(worst, _normal_sf((have - need) / sd_t))
    a.p_stockout_lt = round(worst, 3)
    effective_cover = cover if cover is not None else float("inf")
    if effective_cover < lt or a.p_stockout_lt >= HIGH_P:
        a.risk_level = "HIGH"
    elif effective_cover < lt + MEDIUM_BUFFER_DAYS or a.p_stockout_lt >= MEDIUM_P:
        a.risk_level = "MEDIUM"
    else:
        a.risk_level = "LOW"

    cycle = lt + review_days
    demand_cycle = float(daily[:cycle].sum()) if cycle <= len(daily) else float(daily.sum())
    target = demand_cycle + a.safety_stock
    need = target - pos_total
    a.reorder_qty = round_order(need, ref.moq, ref.case_pack) if need > 0 else 0
    if a.reorder_qty > 0:
        # order as late as possible without breaching safety stock before the order can land; "now" if it already would
        breach = next((i for i, s in enumerate(projected) if s <= a.safety_stock), None)
        delay = 0 if breach is None or a.risk_level == "HIGH" else max(0, breach - lt)
        a.order_by = as_of + timedelta(days=delay)
        a.arrival_date = a.order_by + timedelta(days=lt)
        a.priority = a.risk_level
        a.detail["arrives_after_stockout"] = bool(a.stockout_date and a.arrival_date > a.stockout_date)
    else:
        a.priority = "NONE"
    first_arrival = min((k for k, _ in pos.arrivals), default=None)
    late_incoming = bool(cover is not None and first_arrival is not None and first_arrival > cover)
    a.detail["incoming_arrives_after_stockout"] = late_incoming
    a.detail["next_arrival_in_days"] = first_arrival
    if a.reorder_qty > 0:
        a.detail["action"] = "ORDER"
    elif a.risk_level in ("HIGH", "MEDIUM") and late_incoming:
        a.detail["action"] = "EXPEDITE_INCOMING"          # an open order already covers the quantity but lands after the stock-out
        a.priority = a.risk_level
    else:
        a.detail["action"] = "NONE"
    a.detail.update({"demand_during_lead_time": round(sum_lt, 1), "demand_during_cycle": round(demand_cycle, 1), "target_level": int(math.ceil(target)),
                     "position": pos_total, "lead_time_sd": round(sd_lt, 2), "service_level": 0.95, "review_days": review_days})
    return a


def health_class(coverage_days: float, oldest_age_days: int, trend_pct: float | None) -> str:
    """Healthy | Watch | Slow Moving | Critical Aging (thresholds above; trend_pct = recent demand vs the 56 days before, in percent)."""
    if oldest_age_days >= CRITICAL_AGE_DAYS and coverage_days >= CRITICAL_COVER_DAYS:
        return "Critical Aging"
    declining = trend_pct is not None and trend_pct <= -25
    if coverage_days >= SLOW_DAYS or (oldest_age_days >= AGED_DAYS and coverage_days >= AGED_DAYS) or (declining and coverage_days >= AGED_DAYS):
        return "Slow Moving"
    if coverage_days >= WATCH_COVER_DAYS or oldest_age_days >= WATCH_AGE_DAYS or (trend_pct is not None and trend_pct <= -15):
        return "Watch"
    return "Healthy"
