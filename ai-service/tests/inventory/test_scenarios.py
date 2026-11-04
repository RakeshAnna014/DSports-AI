"""Promotion / clearance what-if engine: deterministic arithmetic, no model, no price is ever written."""
import math

import numpy as np
import pytest

from app.inventory import scenarios as S


def el(e=2.0, pf=0.1, basis="HISTORICAL"):
    return S.Elasticity("Jersey", e, e * 0.8, e * 1.2, pf, 500, basis)


def item(on_hand=1000, daily=10.0, price=1000.0, cost=600.0, e=None, age=0, excess=0, arrivals=()):
    return S.Item("p1", "Test Jersey", price, cost, on_hand, tuple(arrivals), tuple([daily] * 60), e or el(), age, excess)


def test_a_scenario_is_exact_arithmetic():
    r = S.run_item(item(), 30.0, days=14)
    base = 10.0 * 14
    demand = base * (0.7 ** -2.0)
    assert r["baseline_demand"] == base and r["expected_demand"] == pytest.approx(demand, abs=0.1) and r["units_sold"] == pytest.approx(demand, abs=0.1)
    assert r["unit_price"] == 700.0 and r["revenue"] == pytest.approx(demand * 700, abs=1)
    assert r["margin"] == pytest.approx(demand * 100, abs=1) and r["unit_margin_pct"] == pytest.approx(100 / 7, abs=0.1)
    assert r["ending_stock"] == pytest.approx(1000 - demand, abs=0.1)


def test_sales_are_capped_by_stock_and_flag_a_stockout_risk():
    r = S.run_item(item(on_hand=100), 50.0, days=14)
    assert r["units_sold"] == 100 and r["ending_stock"] == 0 and r["stockout_risk"] == "HIGH"
    assert S.run_item(item(on_hand=100_000), 50.0, days=14)["stockout_risk"] == "LOW"
    arriving = S.run_item(item(on_hand=100, arrivals=[(3, 5000)]), 50.0, days=14)
    assert arriving["units_sold"] > 100                           # stock arriving inside the window counts


def test_a_zero_discount_is_the_baseline_and_units_never_fall_with_a_deeper_discount():
    sold = [S.run_item(item(), d)["units_sold"] for d in (0, 10, 20, 30, 40, 50)]
    assert sold == sorted(sold) and sold[0] == 140


def test_revenue_can_fall_while_units_rise_when_demand_is_inelastic():
    low = item(e=el(0.8))
    assert S.run_item(low, 30.0)["revenue"] < S.run_item(low, 0.0)["revenue"]
    assert S.run_item(low, 30.0)["units_sold"] > S.run_item(low, 0.0)["units_sold"]


def test_the_margin_floor_blocks_a_recommendation_and_the_selective_plan_still_finds_value():
    items = [item(on_hand=2000, cost=900.0, age=200, excess=1500), S.Item("p2", "Cheap-cost jersey", 1000.0, 300.0, 2000, (), tuple([10.0] * 60), el(), 200, 1500)]
    out = S.evaluate(items, [0.0, 30.0, 50.0], 14)
    assert out["recommended_discount_pct"] is None                 # 30% leaves 10 INR on a 900 cost: below the 10% floor for p1
    plan = {p["name"]: p["best_discount_pct"] for p in out["per_product_best"]}
    assert plan["Test Jersey"] is None and plan["Cheap-cost jersey"] in (30.0, 50.0)
    assert out["selective"]["discounted_products"] == 1 and out["selective"]["net_benefit"] > 0


def test_aged_excess_stock_makes_a_clearance_worthwhile_that_a_young_one_does_not():
    young = S.evaluate([item(on_hand=3000, cost=500.0, age=10, excess=0)], [0.0, 20.0], 30)
    old = S.evaluate([item(on_hand=3000, cost=500.0, age=240, excess=2500)], [0.0, 20.0], 30)
    assert old["scenarios"][1]["net_benefit"] > young["scenarios"][1]["net_benefit"]
    assert S.writedown_rate(30) == 0 and S.writedown_rate(60) == pytest.approx(0.15) and S.writedown_rate(500) == pytest.approx(0.40)


def test_missing_cost_is_never_invented():
    r = S.run_item(item(cost=None), 30.0)
    assert r["margin"] is None and r["unit_margin_pct"] is None
    out = S.evaluate([item(cost=None)], [0.0, 30.0], 14)
    assert out["scenarios"][1]["net_benefit"] == 0.0 and out["recommended_discount_pct"] is None


def test_elasticity_is_recovered_from_promotion_history_and_flagged_when_there_is_none():
    rng = np.random.default_rng(3)
    n = 400
    truth = 1.8

    def product():
        disc = np.zeros(n)
        for start in range(20, n - 20, 40):
            disc[start:start + 10] = rng.choice([10, 20, 30, 40, 50])
        fitted = np.full(n, 20.0)
        raw = rng.poisson(fitted * (1 - disc / 100) ** (-truth)).astype(float)
        return {"raw": raw, "fitted": fitted, "discount": disc, "unusable": np.zeros(n, dtype=bool), "event": np.zeros(n, dtype=bool)}

    est = S.estimate_elasticity("Jersey", [product() for _ in range(6)])
    assert est.basis == "HISTORICAL" and est.e == pytest.approx(truth, abs=0.15) and est.lo <= est.e <= est.hi
    none = S.estimate_elasticity("Bike", [{"raw": np.full(50, 5.0), "fitted": np.full(50, 5.0), "discount": np.zeros(50), "unusable": np.zeros(50, dtype=bool),
                                           "event": np.zeros(50, dtype=bool)}])
    assert none.basis == "POC_ASSUMPTION" and none.e == S.PRIOR_ELASTICITY[0]


def test_results_state_every_assumption():
    out = S.evaluate([item()], [0.0, 30.0], 14)
    text = " ".join(out["assumptions"])
    assert "elasticity" in text and "Holding cost" in text and "Margin floor" in text and "POC reference data" in text
    assert math.isfinite(out["scenarios"][1]["revenue"])
