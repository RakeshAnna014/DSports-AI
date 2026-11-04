"""Deterministic planning: the worked examples from the Phase 2 brief, reproduced by the code (no LLM, no database)."""
from datetime import date

import numpy as np

from app.inventory import planning as P

AS_OF = date(2026, 10, 8)


def flat(daily: float, n: int = 90) -> np.ndarray:
    return np.full(n, float(daily))


def test_days_of_cover_and_high_risk_example():
    # stock 50, 12 units/day, lead time 7: cover 4.17 days -> HIGH, because it runs out before a new order can arrive
    a = P.assess(AS_OF, P.Position(50), P.Reference(lead_time_days=7), flat(12), sigma_daily=3.0)
    assert a.status == "OK" and a.risk_level == "HIGH"
    assert a.days_of_cover == 4.2 and a.stockout_date == date(2026, 10, 12)
    assert a.reorder_qty > 0 and a.order_by == AS_OF and a.priority == "HIGH"


def test_replenishment_matches_the_worked_example():
    # stock 50, demand during the lead time 84, safety stock 20, nothing incoming, no review period -> 84 + 20 - 50 = 54
    ref = P.Reference(lead_time_days=7, moq=1, case_pack=1)
    a = P.assess(AS_OF, P.Position(50), ref, flat(12), sigma_daily=0.0001, review_days=0,
                 interval_fn=lambda h, s: (s - 20 / P.SERVICE_Z * P._Z80, s + 20 / P.SERVICE_Z * P._Z80))
    assert a.safety_stock == 20 and a.detail["demand_during_lead_time"] == 84.0
    assert a.reorder_qty == 54


def test_order_quantity_respects_moq_and_case_pack():
    assert P.round_order(54, moq=24, case_pack=6) == 54
    assert P.round_order(10, moq=24, case_pack=6) == 24
    assert P.round_order(0, moq=24, case_pack=6) == 0
    assert P.round_order(7, moq=None, case_pack=6) == 12


def test_incoming_stock_reduces_the_recommendation_and_the_risk():
    ref = P.Reference(lead_time_days=7, moq=1, case_pack=1)
    low = P.assess(AS_OF, P.Position(50), ref, flat(12), 3.0)
    covered = P.assess(AS_OF, P.Position(50, arrivals=((3, 300),)), ref, flat(12), 3.0)
    assert covered.risk_level in ("LOW", "MEDIUM") and covered.reorder_qty < low.reorder_qty


def test_overstock_example():
    # stock 1,250 vs a 90-day forecast of 400 -> excess 850 (the brief's example)
    a = P.assess(AS_OF, P.Position(1250, oldest_age_days=130), P.Reference(lead_time_days=10, cost=500.0), flat(400 / 90), 1.0)
    assert a.excess_units == 850 and a.overstock_level == "HIGH" and a.excess_value == 425000.0
    assert a.health_class == "Critical Aging" and a.reorder_qty == 0 and a.priority == "NONE"


def test_missing_lead_time_is_reported_not_guessed():
    a = P.assess(AS_OF, P.Position(30), P.Reference(lead_time_days=None), flat(5), 1.0)
    assert a.status == "MISSING_LEAD_TIME" and a.reorder_qty is None and "lead time" in a.reason.lower()


def test_no_forecast_means_insufficient_data():
    a = P.assess(AS_OF, P.Position(30), P.Reference(lead_time_days=7), None, None)
    assert a.status == "INSUFFICIENT_DATA" and "Insufficient data" in a.reason and a.risk_level is None


def test_health_classes():
    assert P.health_class(20, 10, None) == "Healthy"
    assert P.health_class(80, 10, None) == "Watch"
    assert P.health_class(130, 40, None) == "Slow Moving"
    assert P.health_class(200, 150, None) == "Critical Aging"
    assert P.health_class(95, 20, -30.0) == "Slow Moving"
