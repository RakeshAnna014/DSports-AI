"""Boundary and impossible-input tests for the deterministic engines, with hand-calculated expected values (the arithmetic is in the comments)."""
from datetime import date, timedelta

import numpy as np
import pytest

from app.inventory import forecasting as F
from app.inventory import planning as P
from app.inventory import scenarios as S

AS_OF = date(2026, 10, 8)
START = date(2024, 1, 1)


# ------------------------------------------------------------------------------------------- evaluation integrity (no leakage, same windows)
def test_no_selection_fold_looks_at_the_held_out_window():
    # a fold with origin o (days before the end) tests days [-o, -o + HORIZON); the held-out window is [-HOLDOUT, 0): they must not overlap
    assert F.selection_windows_overlap_holdout() == []
    for o in F.SELECTION_ORIGINS:
        assert -o + F.HORIZON <= -F.HOLDOUT_ORIGIN
    assert F.selection_windows_overlap_holdout((120,)) == [120]          # the previous configuration WOULD have leaked: the guard detects it


def test_every_candidate_model_is_scored_on_exactly_the_same_days():
    rng = np.random.default_rng(5)
    series = {}
    for k in range(4):
        y = rng.poisson(8, 500).astype(float)
        mask = rng.random(500) < 0.1
        series[f"p{k}"] = F.Cleaned(y=y, mask=mask, raw=y, unusable=np.zeros(500, dtype=bool))
    bt, _ = F.run_backtest(series, START, (180, 150), 90)
    counts = {m: sum(len(a) for a in bt.actual[m]) for m in F.MODEL_NAMES}
    assert len(set(counts.values())) == 1 and counts["ridge_regression"] > 0
    first = np.concatenate(bt.actual["moving_average_28"])
    assert all((np.concatenate(bt.actual[m]) == first).all() for m in F.MODEL_NAMES)          # identical actuals for every model


def test_wape_is_undefined_not_zero_or_infinite_when_actual_demand_totals_zero():
    m = F.metrics(np.zeros(10), np.array([1.0] * 10))
    assert np.isnan(m.wape) and np.isnan(m.bias) and m.mape is None and m.mae == 1.0
    assert F.metrics(np.zeros(3), np.zeros(3)).mae == 0.0 and np.isnan(F.metrics(np.zeros(3), np.zeros(3)).wape)
    assert F.sum_wape(F.Backtest(), "ridge_regression", 7) is None


def test_metrics_hand_calculation():
    # actual [10, 0, 5], forecast [8, 1, 5]: |err| = 2, 1, 0 -> WAPE 3/15 = 0.2; MAE 1; RMSE sqrt(5/3); bias (-2 + 1 + 0) / 15
    m = F.metrics(np.array([10.0, 0.0, 5.0]), np.array([8.0, 1.0, 5.0]))
    assert m.wape == pytest.approx(0.2) and m.mae == pytest.approx(1.0) and m.rmse == pytest.approx(np.sqrt(5 / 3)) and m.bias == pytest.approx(-1 / 15)
    assert m.mape == pytest.approx((2 / 10 + 0 / 5) / 2)          # the zero-actual day is excluded from MAPE


# ------------------------------------------------------------------------------------------- data handling
def test_negative_sales_are_treated_as_anomalies_not_as_demand():
    n = 100
    units = np.full((1, n), 6.0)
    units[0, 40] = -3.0                                              # a return booked as negative sales
    c = F.clean_series(units, np.zeros((1, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([1.0]))
    assert c.counts["negative_values"] == 1 and c.mask[40] and c.unusable[40] and np.isnan(c.raw[40])
    assert c.y[40] == pytest.approx(6.0)                             # imputed from the same weekday of earlier weeks, not -3


def test_all_zero_history_yields_zero_forecasts_for_every_model_and_never_negative():
    for m in F.MODEL_NAMES:
        out = F.predict(m, np.zeros(200), START, 90)
        assert (out >= 0).all() and out.max() < 0.01


def test_intermittent_demand_forecast_is_non_negative_and_near_the_rate():
    y = np.random.default_rng(1).poisson(0.2, 600).astype(float)
    out = F.predict("ridge_regression", y, START, 30)
    assert (out >= 0).all() and 0.1 < out.mean() < 0.35


def test_missing_dates_are_rebuilt_from_the_observed_regions_not_zeroed():
    n = 60
    units = np.full((2, n), 5.0)
    units[1, 10:13] = np.nan                                        # region 2 (share 0.5) absent for 3 days
    c = F.clean_series(units, np.zeros((2, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([0.5, 0.5]))
    assert c.raw[10] == pytest.approx(10.0) and not np.isnan(c.raw[10])      # 5 observed / 0.5 share = 10 total
    assert c.counts["missing_days"] == 0                                     # only a day with NO region observed counts as missing
    gone = np.full((2, n), np.nan)
    assert F.clean_series(gone, np.zeros((2, n), dtype=bool), np.zeros(n), np.full(n, -1), np.array([0.5, 0.5])).counts["missing_days"] == n


# ------------------------------------------------------------------------------------------- planning: impossible inputs
def flat(d, n=90):
    return np.full(n, float(d))


@pytest.mark.parametrize("pos,ref,daily", [
    (P.Position(-1), P.Reference(7), flat(5)),                                       # negative stock
    (P.Position(10, ((3, -5),)), P.Reference(7), flat(5)),                           # negative incoming quantity
    (P.Position(10, ((-2, 5),)), P.Reference(7), flat(5)),                           # arrival in the past
    (P.Position(10), P.Reference(-3), flat(5)),                                      # negative lead time
    (P.Position(10), P.Reference(7, moq=-1), flat(5)),
    (P.Position(10), P.Reference(7), -flat(5)),                                      # negative demand forecast
    (P.Position(10), P.Reference(7), np.where(np.arange(90) == 3, np.nan, 5.0)),     # NaN in the forecast
])
def test_the_planning_engine_rejects_impossible_inputs(pos, ref, daily):
    with pytest.raises(ValueError):
        P.assess(AS_OF, pos, ref, daily, 1.0)


def test_zero_lead_time_is_legal():
    a = P.assess(AS_OF, P.Position(100), P.Reference(0), flat(10), 0.0)
    assert a.status == "OK" and a.lead_time_days == 0 and a.risk_level in ("LOW", "MEDIUM") and a.reorder_qty is not None


def test_only_sellable_stock_is_planned_reservations_are_not_modelled():
    # The planner receives ONE number: the sellable position. The POC position has no reservations; with real data the caller must pass
    # DSports available_quantity (sellable stock, which already excludes reserved units). A smaller sellable number raises the risk:
    ref = P.Reference(7, moq=1, case_pack=1)
    full = P.assess(AS_OF, P.Position(120), ref, flat(10), 2.0)
    reserved = P.assess(AS_OF, P.Position(120 - 80), ref, flat(10), 2.0)       # 80 units held by open orders
    assert full.days_of_cover == 12.0 and reserved.days_of_cover == 4.0
    assert full.risk_level != "HIGH" and reserved.risk_level == "HIGH" and reserved.reorder_qty > full.reorder_qty


def test_an_on_time_open_order_is_not_a_late_order_and_vice_versa():
    ref = P.Reference(7, moq=1, case_pack=1)
    base = P.assess(AS_OF, P.Position(40), ref, flat(5), 1.0)
    # 40 on hand at 5/day = 8 days of cover; an order landing on day 3 is on time, an order landing on day 12 is late
    early = P.assess(AS_OF, P.Position(40, ((3, 200),)), ref, flat(5), 1.0)
    late = P.assess(AS_OF, P.Position(40, ((12, 200),)), ref, flat(5), 1.0)
    assert early.detail["incoming_arrives_after_stockout"] is False and late.detail["incoming_arrives_after_stockout"] is True
    assert late.risk_level in ("HIGH", "MEDIUM") and early.risk_level == "LOW"
    assert base.days_of_cover == 8.0


def test_moq_and_case_pack_rounding_boundaries():
    assert P.round_order(0.0, 24, 6) == 0 and P.round_order(-5, 24, 6) == 0          # nothing needed: never an MOQ-sized order
    assert P.round_order(24, 24, 6) == 24 and P.round_order(25, 24, 6) == 30 and P.round_order(1, 24, 6) == 24
    assert P.round_order(0.0001, None, None) == 1                                    # any positive need rounds up to a whole unit
    assert P.round_order(5, 4, 1) == 5


# ------------------------------------------------------------------------------------------- scenarios: impossible inputs
def el():
    return S.Elasticity("X", 2.0, 1.6, 2.4, 0.1, 500, "HISTORICAL")


def item(**kw):
    base = dict(product_id="p", name="P", price=1000.0, cost=600.0, on_hand=1000, arrivals=(), daily=tuple([10.0] * 60), elasticity=el())
    base.update(kw)
    return S.Item(**base)


@pytest.mark.parametrize("discounts,days,floor", [
    ([-5.0], 14, 10.0), ([100.0], 14, 10.0), ([95.0], 14, 10.0), ([float("nan")], 14, 10.0), ([], 14, 10.0),
    ([30.0], 0, 10.0), ([30.0], -3, 10.0), ([30.0], 14, -1.0), ([30.0], 14, 100.0),
])
def test_the_scenario_engine_rejects_impossible_promotions(discounts, days, floor):
    with pytest.raises(ValueError):
        S.evaluate([item()], discounts, days, floor)


@pytest.mark.parametrize("bad", [dict(price=0.0), dict(price=-5.0), dict(cost=-1.0), dict(on_hand=-1), dict(daily=tuple([-1.0] * 60))])
def test_the_scenario_engine_rejects_impossible_products(bad):
    with pytest.raises(ValueError):
        S.evaluate([item(**bad)], [0.0, 30.0], 14)


def test_an_empty_selection_is_rejected():
    with pytest.raises(ValueError):
        S.evaluate([], [30.0], 14)


def test_scenario_hand_calculation_with_rounding():
    # demand 10/day x 14 days = 140 base; 20% off, e = 2: 140 / 0.64 = 218.75 units; price 800; revenue 175,000; margin 218.75 x 200 = 43,750
    r = S.run_item(item(), 20.0, 14)
    assert r["units_sold"] == pytest.approx(218.75, abs=0.06) and r["revenue"] == 175000.0   # 218.74999999999997 in floating point, shown to one decimal and r["margin"] == 43750.0 and r["unit_margin_pct"] == 25.0
    assert S.evaluate([item()], [70.0], 14)["scenarios"][-1]["discount_pct"] == 70.0       # the boundary itself is allowed


def test_the_baseline_is_always_part_of_a_comparison_even_if_not_requested():
    out = S.evaluate([item()], [30.0], 14)
    assert [s["discount_pct"] for s in out["scenarios"]] == [0.0, 30.0] and out["scenarios"][0]["inventory_reduction_units"] == 0.0
