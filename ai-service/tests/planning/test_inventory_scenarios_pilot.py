from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pytest

from app.inventory.planning import Reference
from app.inventory.scenarios import Elasticity
from app.planning import inventory as pi
from app.planning import pilot, scenarios as sc

AS_OF = date(2026, 10, 8)
FIRST = date(2026, 10, 5)
WEEKS = [FIRST + timedelta(weeks=i) for i in range(13)]
REF = Reference(lead_time_days=14, moq=60, case_pack=10, cost=100.0, price=200.0)


def store(loc, region, on_hand, weekly=14.0, **kw):
    return pi.StoreInput(loc, region, on_hand, weekly=[weekly] * 13, **kw)


def test_weekly_forecast_becomes_daily_from_the_as_of_date_and_a_partial_first_week_keeps_only_its_remaining_days():
    d = pi.daily_from_weekly(AS_OF, WEEKS, [14.0] * 13, days=90)
    assert len(d) == 90 and np.allclose(d, 2.0)
    assert np.allclose(pi.daily_from_weekly(date(2026, 10, 5), WEEKS[:1], [7.0], days=10), [1] * 10)             # days after the last planned week continue at its rate
    partial = pi.daily_from_weekly(date(2026, 10, 9), WEEKS[:1], [7.0], days=3)
    assert partial.tolist() == [1.0, 1.0, 1.0] and pi.daily_from_weekly(date(2026, 10, 1), [], [], days=3).tolist() == [0.0, 0.0, 0.0]


def test_the_interval_gives_a_daily_sigma_and_none_without_an_interval():
    assert pi.sigma_daily(WEEKS, None, None) is None
    s = pi.sigma_daily(WEEKS, [10.0] * 13, [18.0] * 13)
    assert s is not None and 0.5 < s < 2.0


def test_a_store_that_will_run_out_before_a_new_order_lands_is_high_risk_and_gets_a_rounded_order():
    recs, transfers, summary = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 4)], REF)
    r = recs[0]
    assert r.risk_level == "HIGH" and r.action == "ORDER" and r.recommended_qty >= 60               # the MOQ lifts a small need to 60
    assert r.recommended_qty % 10 == 0 and r.order_by == AS_OF                                          # case pack of 10; order now when already at risk
    assert r.arrival_date == AS_OF + timedelta(days=14)
    assert "safety stock" in r.reason and summary["order_total"] == r.recommended_qty and not transfers


def test_a_well_stocked_store_needs_nothing():
    recs, _, summary = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 400)], REF)
    assert recs[0].action == "NONE" and recs[0].recommended_qty == 0 and summary["order_total"] == 0


def test_reserved_stock_is_not_sellable_and_incoming_is_counted_once():
    base = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 100)], REF)[0][0]
    reserved = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 100, reserved=90)], REF)[0][0]
    assert reserved.sellable == 10 and reserved.risk_level != base.risk_level or reserved.recommended_qty > base.recommended_qty
    incoming = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 4, arrivals=((5, 200),))], REF)[0][0]
    no_in = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 4)], REF)[0][0]
    assert incoming.incoming == 200 and incoming.recommended_qty < no_in.recommended_qty


def test_moq_and_case_pack_apply_once_to_the_whole_product_order_not_to_each_store():
    stores = [store("S-1", "S", 3), store("S-2", "S", 3), store("N-1", "N", 3)]
    recs, _, summary = pi.plan_product(AS_OF, WEEKS, stores, REF)
    assert summary["order_total"] % 10 == 0 and summary["order_total"] >= 60
    assert sum(r.recommended_qty for r in recs) == summary["order_total"]                              # the stores' quantities add up to ONE supplier order
    assert summary["order_total"] == summary["total_need"] + summary["moq_top_up"]


def test_a_transfer_from_an_overstocked_store_replaces_part_of_a_purchase_and_explains_why():
    stores = [store("S-1", "S", 4), store("S-2", "S", 900)]
    recs, transfers, summary = pi.plan_product(AS_OF, WEEKS, stores, REF)
    assert transfers and transfers[0].from_location == "S-2" and transfers[0].to_location == "S-1"
    t = transfers[0]
    assert t.qty >= 2 and t.lead_days == 3 and t.detail["sooner_than_supplier"] and "keeps its own target level" in t.reason and "sooner" in t.reason
    donor = next(r for r in recs if r.location_id == "S-2")
    assert donor.transfer_out_qty == t.qty and donor.risk_level == "LOW"
    assert summary["transferred"] == t.qty


def test_a_donor_never_gives_below_its_own_target():
    stores = [store("S-1", "S", 4), store("S-2", "S", 900)]
    recs, transfers, _ = pi.plan_product(AS_OF, WEEKS, stores, REF)
    donor_target = next(r for r in recs if r.location_id == "S-2").required_stock
    assert 900 - sum(t.qty for t in transfers) >= donor_target


def test_a_transfer_slower_than_the_supplier_that_also_lands_after_the_stockout_is_not_proposed():
    slow = Reference(lead_time_days=2, moq=None, case_pack=None, cost=100.0, price=200.0)
    stores = [store("S-1", "S", 1, weekly=70.0), store("N-1", "N", 900)]                                # cross-region, 6 days, receiver runs out in hours
    _, transfers, _ = pi.plan_product(AS_OF, WEEKS, stores, slow, cross_region_days=6)
    assert transfers == []


def test_same_region_donors_come_before_cross_region_ones():
    stores = [store("S-1", "S", 4), store("N-1", "N", 900), store("S-2", "S", 600)]
    _, transfers, _ = pi.plan_product(AS_OF, WEEKS, stores, REF)
    assert transfers and transfers[0].from_location == "S-2"


def test_a_missing_lead_time_gives_cover_only_and_no_order_and_no_forecast_is_review():
    recs, _, _ = pi.plan_product(AS_OF, WEEKS, [store("S-1", "S", 4)], Reference(lead_time_days=None))
    assert recs[0].action == "REVIEW" and recs[0].recommended_qty == 0 and "lead time is not recorded" in recs[0].reason
    empty = pi.StoreInput("S-9", "S", 5, weekly=[])
    recs, _, _ = pi.plan_product(AS_OF, WEEKS, [empty], REF)
    assert recs[0].action == "REVIEW" and "insufficient data" in recs[0].reason.lower()


def test_a_store_that_opens_later_sells_nothing_until_it_opens():
    s = store("N-9", "N", 10, open_from=AS_OF + timedelta(days=21))
    a = pi.assess_store(AS_OF, WEEKS, s, REF)
    assert a.status == "OK" and a.forecast_7 == 0.0


# ------------------------------------------------------------------------------------------------ scenarios
def ctx(price=200.0, cost=100.0, elasticity=Elasticity("Bat", 1.5, 1.0, 2.2, 0.1, 100, "HISTORICAL")):
    return sc.ProductCtx("p1", "Bat One", "Cricket", "Bat", price, cost, REF, [store("S-1", "S", 40), store("S-2", "S", 80)], elasticity)


CFG = {"transfer_lead_days": 3, "cross_region_days": 6, "transfer_min_qty": 2, "transfer_unit_cost": None}


def run(kind, params, c=None):
    return sc.evaluate(kind, params, [c or ctx()], AS_OF, WEEKS, CFG)


def test_growth_raises_demand_and_stock_needed_and_says_a_target_is_not_a_forecast():
    r = run("GROWTH_TARGET", {"growth_pct": 10})
    s = r["summary"]
    assert s["scenario"]["demand_units"] == pytest.approx(s["baseline"]["demand_units"] * 1.1, rel=1e-3)
    assert s["delta"]["order_units"] >= 0 and "not a forecast" in r["items"][0]["scenario"]["note"] and r["status"] == "SIMULATION"
    assert any("not a forecast" in x for x in r["limits"])


def test_a_promotion_uses_the_elasticity_of_the_category_and_reports_its_basis_and_range():
    r = run("PROMOTION", {"discount_pct": 20, "weeks": 2, "start_week_index": 0})
    it = r["items"][0]["scenario"]
    assert it["uplift_multiplier"] == pytest.approx(0.8 ** -1.5, rel=1e-3) and it["elasticity_basis"] == "HISTORICAL"
    assert it["uplift_range"][0] < it["uplift_multiplier"] < it["uplift_range"][1]
    assert it["promotion_units"] > it["baseline_promotion_units"] and it["revenue"] is not None and it["margin"] is not None
    assert r["summary"]["scenario"]["demand_units"] != r["summary"]["baseline"]["demand_units"]          # pull-forward takes some back from later weeks


def test_a_promotion_without_an_elasticity_estimate_is_refused_not_guessed():
    with pytest.raises(sc.ScenarioError):
        run("PROMOTION", {"discount_pct": 20, "weeks": 2}, ctx(elasticity=None))


def test_margin_and_budget_are_only_given_when_price_and_cost_exist():
    r = run("GROWTH_TARGET", {"growth_pct": 10}, ctx(price=None, cost=None))
    assert r["summary"]["scenario"]["revenue"] is None and r["summary"]["scenario"]["margin"] is None and r["summary"]["scenario"]["order_cost"] is None


def test_a_longer_lead_time_never_reduces_what_has_to_be_ordered_and_a_higher_service_level_adds_safety_stock():
    base = run("LEAD_TIME", {"delta_days": 0})["summary"]
    longer = run("LEAD_TIME", {"delta_days": 14})["summary"]
    assert longer["scenario"]["order_units"] >= base["baseline"]["order_units"]
    safer = run("SAFETY_STOCK", {"service_level": 0.99})
    assert safer["summary"]["scenario"]["order_units"] >= safer["summary"]["baseline"]["order_units"] and safer["items"][0]["scenario"]["service_z"] > 1.645


def test_a_transfer_scenario_compares_the_risk_of_both_stores_and_refuses_more_than_is_sellable():
    p = {"product_id": "p1", "from_location": "S-2", "to_location": "S-1", "qty": 30}
    r = run("TRANSFER", p)
    st = r["items"][0]["scenario"]["stores"]
    assert set(st) == {"S-1", "S-2"} and st["S-1"]["coverage_days_after"] > st["S-1"]["coverage_days_before"]
    with pytest.raises(sc.ScenarioError):
        run("TRANSFER", {**p, "qty": 500})
    with pytest.raises(sc.ScenarioError):
        run("TRANSFER", {**p, "to_location": "S-2"})


@pytest.mark.parametrize("kind,params", [("NOPE", {}), ("GROWTH_TARGET", {"growth_pct": 900}), ("PROMOTION", {"discount_pct": 90, "weeks": 2}), ("PROMOTION", {"discount_pct": 10, "weeks": 99}),
                                         ("LEAD_TIME", {}), ("SAFETY_STOCK", {"service_level": 1.5})])
def test_invalid_parameters_are_refused(kind, params):
    with pytest.raises(sc.ScenarioError):
        run(kind, params)


def test_running_a_scenario_does_not_change_the_baseline_inputs():
    c = ctx()
    before = [list(s.weekly) for s in c.stores]
    run("GROWTH_TARGET", {"growth_pct": 50}, c)
    assert [list(s.weekly) for s in c.stores] == before


# ------------------------------------------------------------------------------------------------ pilot
def comp(i, price, rate=2.0):
    return {"product_id": f"c{i}", "name": f"Comparable {i}", "sport": "Cricket", "category": "Bat", "price": price, "weekly_units": rate}


def test_comparables_are_the_same_sport_and_category_nearest_price_and_never_the_product_itself():
    cat = [comp(1, 1000), comp(2, 5000), comp(3, 1200), {**comp(4, 1100), "category": "Ball"}, {**comp(5, 1100), "sport": "Football"}, {**comp(6, 1150), "product_id": "new"}]
    got = pilot.pick_comparables({"product_id": "new", "sport": "Cricket", "category": "Bat", "selling_price": 1100}, cat)
    assert [c["product_id"] for c in got] == ["c1", "c3", "c2"]


def rates(loc_rates):
    return {f"c{i}": {loc: r * (1 + 0.1 * i) for loc, r in loc_rates.items()} for i in range(1, 5)}


def test_a_pilot_estimate_is_a_low_confidence_range_with_assumptions_and_spreads_across_regions():
    stores = {"S-1": 2.0, "S-2": 4.0, "N-1": 1.0, "E-1": 3.0}
    est = pilot.estimate([comp(i, 1000) for i in range(1, 5)], rates(stores), {k: 104 for k in stores}, {"S-1": "S", "S-2": "S", "N-1": "N", "E-1": "E"}, duration_weeks=8, max_stores=3)
    assert est["status"] == "ESTIMATED" and est["confidence"] == "LOW" and est["assumptions"]
    ids = [s["location_id"] for s in est["stores"]]
    assert set(ids) == {"S-2", "E-1", "N-1"}                                                          # one per region first, though S-1 outsells N-1
    top = est["stores"][0]
    assert top["weekly_lo"] <= top["weekly_mid"] <= top["weekly_hi"] and top["pilot_qty"] >= top["expected_units_hi"]


def test_too_few_comparables_or_too_little_store_history_gives_no_estimate():
    assert pilot.estimate([comp(1, 1)], {}, {}, {}, 8)["status"] == "INSUFFICIENT_COMPARABLES"
    stores = {"S-1": 2.0}
    est = pilot.estimate([comp(i, 1000) for i in range(1, 5)], rates(stores), {"S-1": 5}, {"S-1": "S"}, 8)
    assert est["status"] == "INSUFFICIENT_STORE_HISTORY" and est["stores"] == []


def est_fixture():
    return {"stores": [{"location_id": "S-1", "weekly_lo": 2.0, "weekly_mid": 3.0, "weekly_hi": 4.0}]}


def obs(n, units, days=7):
    return [{"location_id": "S-1", "units": units, "available_days": days} for _ in range(n)]


def test_a_pilot_is_not_judged_on_too_few_observations_or_weeks_with_the_product_missing():
    assert pilot.reassess(est_fixture(), obs(3, 10))["decision"] == "CONTINUE_TESTING"
    mostly_out = pilot.reassess(est_fixture(), obs(12, 0, days=2))
    assert mostly_out["decision"] == "CONTINUE_TESTING" and mostly_out["excluded_for_availability"] == 12       # a stock-out is not low demand


def test_a_pilot_expands_continues_or_stops_by_comparing_with_the_estimated_range():
    assert pilot.reassess(est_fixture(), obs(10, 5))["decision"] == "EXPAND"
    assert pilot.reassess(est_fixture(), obs(10, 3))["decision"] == "CONTINUE_TESTING"
    d = pilot.reassess(est_fixture(), obs(10, 1))
    assert d["decision"] == "DISCONTINUE" and d["sufficient"] and "Margin is not judged" in d["note"]
