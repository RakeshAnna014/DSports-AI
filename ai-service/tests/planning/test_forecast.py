from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np
import pytest

from app.planning import demo
from app.planning import forecast as fc

START = date(2024, 10, 7)                                    # a Monday


def weeks(n: int, start: date = START) -> list[date]:
    return [start + timedelta(weeks=i) for i in range(n)]


def make_series(n_weeks: int = 104, seed: int = 1, n_products: int = 3, n_stores: int = 4, season: float = 0.3) -> tuple[list[date], list[date], list[fc.SeriesInput]]:
    """Deterministic fixture: Poisson weekly sales with a yearly pattern and a different level per product and store."""
    rng = np.random.default_rng(seed)
    wk = weeks(n_weeks)
    fut = weeks(13, wk[-1] + timedelta(weeks=1))
    out = []
    for p in range(n_products):
        for s in range(n_stores):
            level = 3 + 6 * p + 2 * s
            lam = np.array([level * (1 + season * math.sin(2 * math.pi * fc.week_slot(w) / 52)) for w in wk])
            out.append(fc.SeriesInput(f"p{p}", f"s{s}", "Cricket", "Bat", "S" if s < 2 else "N", rng.poisson(lam).astype(float), None, 1.0 + 0.1 * s))
    return wk, fut, out


def test_the_baseline_is_the_mean_of_the_last_four_weeks():
    assert fc.m_ma4(np.array([1, 2, 3, 4, 10.0]), 3).tolist() == [4.75, 4.75, 4.75]
    assert fc.m_ma4(np.array([]), 2).tolist() == [0.0, 0.0]


def test_seasonal_index_is_one_without_enough_history_and_has_mean_one_with_it():
    wk = weeks(60)
    assert (fc.seasonal_index(wk, np.ones(60) * 5) == 1).all()
    wk = weeks(104)
    g = np.array([5 * (1 + 0.4 * math.sin(2 * math.pi * fc.week_slot(w) / 52)) for w in wk])
    ix = fc.seasonal_index(wk, g)
    assert math.isclose(float(ix.mean()), 1.0, abs_tol=1e-9) and ix.max() > 1.1 and ix.min() < 0.9


def test_croston_gives_a_small_flat_rate_for_intermittent_demand_and_zero_for_none():
    y = np.array([0, 0, 5, 0, 0, 0, 5, 0, 0, 0, 5, 0], dtype=float)
    f = fc.m_croston_sba(y, 4)
    assert f.std() == 0 and 0.5 < f[0] < 2.0                      # about 5 every 4 weeks
    assert fc.m_croston_sba(np.zeros(10), 3).tolist() == [0.0, 0.0, 0.0]


def test_buckets_follow_the_recent_volume_and_zero_share():
    assert fc.bucket_of(np.array([0, 0, 0, 5, 0, 0, 0, 0.0]))[0] == "INTERMITTENT"
    assert fc.bucket_of(np.full(30, 2.0))[0] == "LOW"
    assert fc.bucket_of(np.full(30, 10.0))[0] == "MEDIUM"
    assert fc.bucket_of(np.full(30, 40.0))[0] == "HIGH"


def test_stockout_weeks_are_imputed_in_training_only_and_the_actuals_are_untouched():
    y = np.array([10, 10, 10, 1, 10.0])
    so = np.array([0, 0, 0, 5, 0.0])
    imputed = fc.impute_censored(y, so)
    assert imputed[3] == 10.0 and y[3] == 1.0                      # the input is not modified
    assert fc.impute_censored(y, None) is y


def test_a_full_run_produces_non_negative_forecasts_ordered_intervals_and_the_baseline():
    wk, fut, series = make_series()
    f = fc.Forecaster(wk, fut, series, min_history=26, selection_origins=2, step=4)
    res = f.run()
    assert len(res["series"]) == len(series) and set(res["chosen"]) == set(fc.BUCKETS)
    for s in res["series"]:
        assert s.status == "OK" and (s.forecast >= 0).all() and len(s.forecast) == 13
        assert (s.lo <= s.forecast + 1e-9).all() and (s.hi >= s.forecast - 1e-9).all()
        assert s.baseline.shape == (13,) and s.confidence in ("HIGH", "MEDIUM", "LOW")


def test_a_forecast_does_not_use_the_future_changing_data_after_the_origin_cannot_change_it():
    """No leakage: the forecast made at origin t0 must be identical whatever the data AFTER t0 says."""
    wk, fut, series = make_series()
    t0 = 80
    f1 = fc.Forecaster(wk, fut, series, min_history=26)
    a = f1.forecast_series(0, t0, wk[t0:t0 + 13], 13, f1._sport_indexes(t0), f1._parent_series(t0))
    mutated = [fc.SeriesInput(s.product_id, s.location_id, s.sport, s.category, s.region, s.y.copy(), None, s.size_factor) for s in series]
    for s in mutated:
        s.y[t0:] = 999.0                                              # the future is completely different
    f2 = fc.Forecaster(wk, fut, mutated, min_history=26)
    b = f2.forecast_series(0, t0, wk[t0:t0 + 13], 13, f2._sport_indexes(t0), f2._parent_series(t0))
    for m in fc.CANDIDATES:
        assert np.allclose(a[m], b[m]), f"{m} used data after the origin"


def test_the_backtest_trains_only_on_weeks_before_the_origin_and_judges_on_the_weeks_after():
    wk, fut, series = make_series()
    f = fc.Forecaster(wk, fut, series, min_history=26)
    obs = f.backtest([80])
    assert obs and all(1 <= o.h <= 13 for o in obs)
    s0 = series[0]
    by_h = {o.h: o.actual for o in obs if o.series == 0}
    assert all(by_h[h] == s0.y[80 + h - 1] for h in by_h)           # the actuals are the real weeks 80.., never something computed from the training window


def test_a_series_with_too_little_history_is_not_backtested_and_falls_back():
    wk, fut, series = make_series()
    short = fc.SeriesInput("pX", "sNew", "Cricket", "Bat", "S", np.concatenate([np.full(94, np.nan), np.array([3, 4, 5, 3, 4, 6, 5, 4, 5, 6.0])]), None, 1.0)
    f = fc.Forecaster(wk, fut, series + [short], min_history=26)
    assert all(f.series[o.series].location_id != "sNew" for o in f.backtest([80]))
    out = {(s.product_id, s.location_id): s for s in f.run()["series"]}
    fb = out[("pX", "sNew")]
    # pX has no sales in any other store, so its region total is just this store: with 10 weeks it is a TOP_DOWN fallback, always low confidence
    assert fb.status == "FALLBACK" and fb.confidence == "LOW" and fb.method == "TOP_DOWN"


def test_a_product_with_no_sales_anywhere_is_insufficient_not_forecast_from_nothing():
    wk, fut, series = make_series()
    dead = fc.SeriesInput("pNone", "s0", "Cricket", "Bat", "S", np.full(104, np.nan), None, 1.0)
    out = {(s.product_id, s.location_id): s for s in fc.Forecaster(wk, fut, series + [dead], min_history=26).run()["series"]}
    d = out[("pNone", "s0")]
    assert d.status == "INSUFFICIENT_DATA" and d.confidence == "NONE" and d.forecast.size == 0


def test_a_planned_store_is_forecast_from_its_opening_week_by_comparison_with_low_confidence():
    wk, fut, series = make_series()
    planned = [fc.SeriesInput(f"p{p}", "sPlanned", "Cricket", "Bat", "S", np.full(104, np.nan), None, 1.0, None, "PLANNED", first_forecast_week=3) for p in range(3)]
    out = {(s.product_id, s.location_id): s for s in fc.Forecaster(wk, fut, series + planned, min_history=26).run()["series"]}
    s = out[("p0", "sPlanned")]
    assert s.status == "FALLBACK" and s.method == "COMPARABLE_STORE" and s.confidence == "LOW"
    assert (s.forecast[:3] == 0).all() and (s.forecast[3:] > 0).all()                       # nothing before it opens


def test_top_down_stores_add_up_to_the_region_forecast_for_the_same_method():
    wk, fut, series = make_series()
    f = fc.Forecaster(wk, fut, series, min_history=26)
    idxs, parents = f._sport_indexes(104), f._parent_series(104)
    region_s = [i for i, s in enumerate(series) if s.product_id == "p0" and s.region == "S"]
    total = sum(f.forecast_series(i, 104, fut, 13, idxs, parents, ("TOP_DOWN",))["TOP_DOWN"] for i in region_s)
    pary = parents[("p0", "S")]
    parent_fc = fc.m_seasonal_level(pary, 13, weeks_hist=wk, weeks_fut=fut, idx=idxs["Cricket"])
    assert np.allclose(total, parent_fc)                                                    # the shares sum to 1: the stores reconcile to the region


def test_selection_never_beats_a_simple_method_by_claiming_it_on_data_it_was_chosen_with():
    wk, fut, series = make_series(seed=7)
    res = fc.Forecaster(wk, fut, series, min_history=26, selection_origins=2, step=4).run()
    assert res["heldout_origin"] > max(res["selection_origins"]) + 0
    sel_ends = [t + 13 for t in res["selection_origins"]]
    assert max(sel_ends) <= res["held_t0"]                                                  # every selection test window ends before the held-out window begins


def test_metrics_handle_zero_actuals_and_report_bias_sign():
    m = fc.metric_row(np.array([0, 0, 0.0]), np.array([1, 1, 1.0]))
    assert m["wape"] is None and m["bias"] is None and m["mae"] == 1.0                       # WAPE is undefined when actual sales are all zero: not shown as a percentage
    m = fc.metric_row(np.array([10, 10.0]), np.array([12, 12.0]))
    assert math.isclose(m["wape"], 0.2) and m["bias"] > 0                                    # over-forecast: positive bias
    assert fc.metric_row(np.array([np.nan, 5.0]), np.array([1.0, 5.0]))["n_obs"] == 1       # unknown weeks are skipped


def test_horizon_groups_cover_every_horizon_once():
    assert [fc.horizon_group(h) for h in (1, 2, 4, 5, 8, 9, 13)] == ["H1", "H2_4", "H2_4", "H5_8", "H5_8", "H9_PLUS", "H9_PLUS"]


def test_forecasting_is_deterministic():
    wk, fut, series = make_series()
    a = fc.Forecaster(wk, fut, series, min_history=26, selection_origins=2).run()["series"]
    b = fc.Forecaster(wk, fut, series, min_history=26, selection_origins=2).run()["series"]
    assert all(np.array_equal(x.forecast, y.forecast) for x, y in zip(a, b))


# ------------------------------------------------------------------------------------------------ the demonstration split
def test_stores_of_a_region_add_up_to_the_region_every_week_and_closed_stores_get_nothing():
    layout = [s for s in demo.store_layout(date(2026, 10, 8)) if s.region == "S"]
    rng = np.random.default_rng(3)
    week = date(2026, 6, 1)
    probs = demo.store_probs(layout, "Cricket", week)
    assert math.isclose(probs.sum(), 1.0)
    parts = demo.split_units(137, probs, rng)
    assert parts.sum() == 137
    new_store = next(i for i, s in enumerate(layout) if s.id == "S-Z2-S2")
    assert parts[new_store] == 0 or demo.store_probs(layout, "Cricket", date(2026, 9, 28))[new_store] > 0
    early = demo.store_probs(layout, "Cricket", date(2026, 1, 5))
    assert early[new_store] == 0                                       # not open yet in January: no sales, and no share


def test_stock_allocation_sums_exactly_and_is_deterministic():
    probs = np.array([0.5, 0.3, 0.2])
    assert demo.allocate(10, probs).tolist() == [5, 3, 2]
    a = demo.allocate(7, probs)
    assert a.sum() == 7 and demo.allocate(7, probs).tolist() == a.tolist()
    assert demo.allocate(0, probs).sum() == 0


def test_the_demo_network_is_labelled_and_has_a_new_and_a_planned_store():
    layout = demo.store_layout(date(2026, 10, 8))
    assert len(layout) == 20 and {s.status for s in layout} == {"ACTIVE", "PLANNED"}
    assert next(s for s in layout if s.id == "S-Z2-S2").opened_on == date(2026, 8, 27)
    assert "DEMONSTRATION" in demo.seasonal_note()


def test_method_selection_is_bias_aware_so_a_zero_forecast_is_not_chosen_for_intermittent_demand():
    """Pooled WAPE alone scores an all-zero forecast exactly 100% and an unbiased forecast of sparse demand worse (150% here), so it would pick the method that predicts nothing. WAPE + |bias| must not."""
    y = np.zeros(40)
    y[[3, 9, 15, 21, 27, 33]] = 2.0                          # intermittent history, then a test window with a single sale
    y[32] = 3.0
    weeks = [date(2025, 1, 6) + timedelta(days=7 * i) for i in range(40)]
    f = fc.Forecaster(weeks, weeks[-4:], [fc.SeriesInput("p", "s", "Cricket", "Bat", "S", y)], min_history=26)
    f._origin_ts = [30]
    actual = np.array([0.0, 0.0, 3.0, 0.0])
    obs = []
    for h in range(4):
        fcs = {m: 4.0 for m in fc.CANDIDATES}               # every other candidate is clearly worse
        fcs["ETS_DAMPED"] = 0.0                              # predicts nothing: WAPE exactly 1.0, bias -100%
        fcs["CROSTON_SBA"] = 0.75                            # unbiased over the window, WAPE 1.5
        obs.append(fc.Obs(0, 0, h + 1, float(actual[h]), fcs))
    chosen, table = f.select_methods(obs)
    bucket = fc.bucket_of(fc.trim(y[:30]))[0]
    assert table[bucket]["wape"]["ETS_DAMPED"] == pytest.approx(1.0) and table[bucket]["wape"]["CROSTON_SBA"] == pytest.approx(1.5)
    assert chosen[bucket] == "CROSTON_SBA"
    assert table[bucket]["criterion"].startswith("WAPE + |bias|") and table[bucket]["bias"]["ETS_DAMPED"] == pytest.approx(-1.0)

def test_unknown_weeks_inside_a_series_never_produce_a_non_finite_forecast():
    """A store that did not trade for a few weeks leaves NaN inside the series. Every method must still return finite, non-negative numbers (a NaN once reached the plan lines and the database)."""
    rng = np.random.default_rng(5)
    y = rng.poisson(2.0, 60).astype(float)
    y[[40, 41, 50]] = np.nan
    weeks = [date(2025, 1, 6) + timedelta(days=7 * i) for i in range(60)]
    future = [weeks[-1] + timedelta(days=7 * (i + 1)) for i in range(8)]
    series = [fc.SeriesInput("p", "s", "Cricket", "Bat", "S", y), fc.SeriesInput("p", "t", "Cricket", "Bat", "S", rng.poisson(2.0, 60).astype(float))]
    f = fc.Forecaster(weeks, future, series, min_history=26)
    idxs, parents = f._sport_indexes(60), f._parent_series(60)
    out = f.forecast_series(0, 60, future, 8, idxs, parents)
    for method, values in out.items():
        assert np.isfinite(values).all() and (values >= 0).all(), method
    assert out["MA4"].mean() > 0.5                         # unknown weeks were not turned into zeros