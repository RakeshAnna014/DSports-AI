"""Forecasting: statistical models on series whose structure is KNOWN, so the tests can say what a correct model must find."""
from datetime import date, timedelta

import numpy as np
import pytest

from app.inventory import forecasting as F

START = date(2024, 1, 1)


def series(n=730, base=10.0, weekly=True, trend=0.0, noise=0.0, seed=1):
    rng = np.random.default_rng(seed)
    t = np.arange(n)
    dow = np.array([(START + timedelta(days=int(i))).weekday() for i in t])
    y = base * (1 + trend * t / 365) * (np.where(dow >= 5, 1.5, 1.0) if weekly else 1.0)
    return np.maximum(y + rng.normal(0, noise, n), 0)


def test_weekly_models_beat_a_flat_average_on_a_weekly_pattern():
    y = series()
    train, test = y[:-28], y[-28:]
    for model in ("seasonal_naive_7", "holt_winters", "ridge_regression"):
        err = np.abs(F.predict(model, train, START, 28) - test).sum()
        flat = np.abs(F.predict("moving_average_28", train, START, 28) - test).sum()
        assert err < flat * 0.5, model


def test_ridge_follows_a_trend_and_never_forecasts_negative_demand():
    y = series(trend=0.6, weekly=False)
    f = F.predict("ridge_regression", y, START, 90)
    assert f[-1] > f[0] > y[-28:].mean() * 0.95 and (f >= 0).all()
    zeros = np.zeros(300)
    assert (F.predict("holt_winters", zeros, START, 30) == 0).all() and (F.predict("ridge_regression", zeros, START, 30) < 0.01).all()


def test_forecasts_are_deterministic():
    y = series(noise=1.5)
    for model in F.MODEL_NAMES:
        assert (F.predict(model, y, START, 30) == F.predict(model, y, START, 30)).all()


def test_metrics_are_computed_on_the_unmasked_days_only_and_match_the_definitions():
    a = np.array([10.0, 20.0, 0.0, 30.0])
    f = np.array([12.0, 18.0, 1.0, 30.0])
    m = F.metrics(a, f)
    assert m.wape == pytest.approx((2 + 2 + 1 + 0) / 60)
    assert m.mae == pytest.approx(1.25) and m.rmse == pytest.approx(np.sqrt((4 + 4 + 1 + 0) / 4))
    assert m.mape == pytest.approx((0.2 + 0.1 + 0.0) / 3)          # MAPE skips the zero-demand day
    assert m.bias == pytest.approx((2 - 2 + 1 + 0) / 60)
    assert np.isnan(F.metrics(np.array([]), np.array([])).wape)


def test_clean_series_excludes_stockouts_promotions_and_events_but_keeps_ordinary_days():
    n = 120
    units = np.full((2, n), 5.0)
    stockout = np.zeros((2, n), dtype=bool)
    stockout[0, 10:13] = True                                  # region 0 (60% of demand) was out of stock for 3 days
    discount = np.zeros(n)
    discount[40:45] = 30
    events = np.full(n, -1)
    events[70:75] = 0
    units[0, 90] = np.nan                                       # a missing region-day
    c = F.clean_series(units, stockout, discount, events, np.array([0.6, 0.4]))
    assert c.mask[10:13].all() and c.mask[40:52].all() and c.mask[70:75].all()    # promo (+7-day dip), event, stock-out
    assert not c.mask[20] and not c.mask[100]
    assert c.counts["stockout_days"] == 3 and c.counts["event_days"] == 5
    assert c.raw[90] == pytest.approx(5.0 / 0.4)                # the 60% region is missing: the observed 40% (5 units) is scaled up to a full-demand estimate
    assert (c.y[c.mask] > 0).all()                              # imputed, not left as zero


def test_event_uplift_is_measured_against_the_baseline_not_assumed():
    from app.inventory import events as E
    n = 400
    days = [START + timedelta(days=i) for i in range(n)]
    base = series(n, base=8.0, weekly=False)
    raw = base.copy()
    raw[300:320] *= 2.0                                          # a REAL 2x event
    fitted = np.full(n, 8.0)
    ev = {"id": "e1", "sport": "Cricket", "start_date": days[300], "end_date": days[319], "popularity": 1.0, "event_type": "tournament"}
    prods = [{"sport": "Cricket", "category": "Bat", "raw": raw, "fitted": fitted, "unusable": np.zeros(n, dtype=bool)} for _ in range(3)]
    ev1 = E.measure_event(ev, days, prods)
    assert len(ev1) == 1 and ev1[0].uplift == pytest.approx(2.0, abs=0.05) and ev1[0].n_products == 3
    est = E.estimate_upcoming({**ev, "id": "e2", "popularity": 1.0}, [(ev, ev1)], ["Bat", "Ball"])
    assert est["Bat"]["uplift"] == pytest.approx(2.0, abs=0.05) and est["Bat"]["basis"] == "HISTORICAL"
    assert est["Ball"]["basis"] == "HISTORICAL_SPORT_LEVEL" and est["Ball"]["uplift"] >= 1.0     # never measured for this category: the sport-wide average, labelled as such


def test_with_no_history_the_event_uplift_is_a_flagged_assumption_never_a_measurement():
    from app.inventory import events as E
    est = E.estimate_upcoming({"id": "x", "sport": "Cricket", "event_type": "tournament", "popularity": 1.0}, [], ["Bat"])
    assert est["Bat"]["basis"] == "POC_ASSUMPTION" and est["Bat"]["n_events"] == 0


def test_a_bigger_event_scales_the_uplift_by_popularity_with_diminishing_effect():
    from app.inventory import events as E
    base_ev = {"id": "p", "sport": "Cricket", "event_type": "tournament", "popularity": 1.0}
    past = [(base_ev, [E.Evidence("p", "Cricket", "Bat", 2.0, 1.8, 2.2, 5)])]
    small = E.estimate_upcoming({"id": "n", "sport": "Cricket", "event_type": "tournament", "popularity": 1.0}, past, ["Bat"])["Bat"]["uplift"]
    big = E.estimate_upcoming({"id": "n", "sport": "Cricket", "event_type": "tournament", "popularity": 2.0}, past, ["Bat"])["Bat"]["uplift"]
    assert small == pytest.approx(2.0) and 2.0 < big < 3.0


def test_applying_an_event_changes_only_the_days_inside_its_window():
    from app.inventory import events as E
    daily = np.full(90, 10.0)
    ev = {"start_date": date(2026, 11, 5), "end_date": date(2026, 11, 14)}
    out = E.apply_to_forecast(daily, date(2026, 10, 8), ev, 2.0)
    assert out[:28].sum() == 280 and out[28:38].sum() == 200 and out[38:].sum() == 520
    assert daily.sum() == 900                                    # the baseline array is untouched
