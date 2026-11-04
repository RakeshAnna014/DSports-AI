"""The AI planning analysis: findings are deterministic, separate facts from hypotheses, withhold what the data cannot support, and propose (never apply) changes."""
from __future__ import annotations

import copy

from app.planning import insights as ins

TH = ins.Thresholds()


def evidence(**over):
    ev = {
        "plan": {"id": "p1", "name": "Q3 bats", "status": "DRAFT", "source": "DSPORTS_LIVE", "version": 3},
        "data_through": "2026-10-14", "forecast_generated": "2026-10-15T10:00:00", "model_version": "weekly-v1", "forecast_run_id": "r1",
        "history_weeks": 4, "lines": {"lines": 100, "low_confidence": 92, "no_forecast": 3},
        "plan_first_week": "2026-10-19", "plan_weeks": 4, "plan_weeks_list": ["2026-10-19", "2026-10-26", "2026-11-02", "2026-11-09"],
        "n_recent": 4, "recent_rows": [], "variance_rows": [], "store_weeks": [], "trend_rows": [],
        "alloc": {"generated": False, "high_risk": 0, "with_surplus": 0, "units_to_order": 0, "transfers": 0, "transfers_avoiding_orders": 0, "top_risks": []},
        "focus_rows": [],
    }
    ev.update(over)
    return ev


def row(product="a", store="S1", cat="Cricket / Bat", f=10.0, model=10.0, r_sum=20.0, stockout=None):
    return {"product_id": product, "location_id": store, "name": f"Product {product}", "category_label": cat, "f_avg": f, "model_avg": model, "r_sum": r_sum, "stockout_days": stockout, "rc_n": 4}


def test_thin_live_history_is_a_high_severity_readiness_finding_and_online_only_is_stated():
    findings, unavailable = ins.build(evidence(), TH)
    f = next(x for x in findings if x["kind"] == "DATA_READINESS")
    assert f["severity"] == "HIGH" and "closed week" in f["statement"] and "92" in " ".join(f["facts"]) and f["confidence"] == "HIGH"
    assert any("physical-store" in u["why"] for u in unavailable)
    assert "LIVE" in f["provenance"] and "DEMO" not in f["provenance"]


def test_demonstration_plans_are_labelled_demonstration_in_every_finding():
    ev = evidence(plan={"id": "p1", "name": "Demo", "status": "DRAFT", "source": "POC_SYNTHETIC", "version": 1}, history_weeks=104, lines={"lines": 100, "low_confidence": 80, "no_forecast": 0},
                  recent_rows=[row(f="30", r_sum=40.0)])
    findings, _ = ins.build(ev, TH)
    assert findings and all("DEMO" in f["provenance"] and "LIVE" not in f["provenance"] for f in findings)
    assert "demonstration" in ins.rules_summary(ev, findings, []).lower()


def test_forecast_far_above_recent_sales_is_flagged_with_facts_hypotheses_evidence_and_a_proposal():
    ev = evidence(recent_rows=[row("a", f=14.0, model=14.0, r_sum=24.0), row("b", f=10.0, model=10.0, r_sum=16.0)])      # forecast 24/week vs 10/week recent
    findings, _ = ins.build(ev, TH)
    f = next(x for x in findings if x["kind"] == "FORECAST_VS_RECENT")
    assert "24" in f["statement"] and "10" in f["statement"] and "+140%" in f["statement"] and f["severity"] == "HIGH"
    assert f["facts"] and f["hypotheses"] and all("stock-out" in h or "one-off" in h or "extrapolating" in h for h in f["hypotheses"])
    assert f["evidence"][0]["tab"] == "workspace" and f["freshness"]["data_through"] == "2026-10-14" and f["assumptions"]
    p = f["proposal"]
    assert p["kind"] == "MANAGER_FORECAST" and p["reason_code"] == "DATA_CORRECTION" and p["items"] and "blend" in p["basis"].lower()
    a = next(i for i in p["items"] if i["product_id"] == "a")
    assert a["model_per_week"] == 14.0 and a["recent_per_week"] == 6.0 and a["proposed_per_week"] == 10.0          # 50% of 6 + 50% of 14


def test_small_differences_are_not_flagged_and_noise_below_the_unit_floor_is_ignored():
    ev = evidence(recent_rows=[row("a", f=10.0, r_sum=36.0)])                      # 10 vs 9 per week: +11%
    assert not [f for f in ins.build(ev, TH)[0] if f["kind"] == "FORECAST_VS_RECENT"]
    tiny = evidence(recent_rows=[row("a", f=3.0, r_sum=4.0)])                       # +200% but 2 units a week
    assert not [f for f in ins.build(tiny, TH)[0] if f["kind"] == "FORECAST_VS_RECENT"]


def test_too_few_recent_weeks_withholds_the_comparison_and_says_what_is_needed():
    ev = evidence(n_recent=2, recent_rows=[row()])
    findings, unavailable = ins.build(ev, TH)
    assert not [f for f in findings if f["kind"] == "FORECAST_VS_RECENT"]
    assert any(u["analysis"] == "Forecast versus recent sales" and "3 closed weeks" in u["why"] for u in unavailable)


def test_recorded_stockouts_become_a_hypothesis_that_low_recent_sales_may_reflect_availability():
    ev = evidence(recent_rows=[row("a", f=30.0, model=30.0, r_sum=40.0, stockout=6)])
    f = next(x for x in ins.build(ev, TH)[0] if x["kind"] == "FORECAST_VS_RECENT")
    assert f["hypotheses"][0].startswith("6 stock-out day(s)")
    assert all("because" not in x.lower() for x in f["facts"])                       # facts never assert a cause


def test_proposals_only_cover_lines_with_a_clear_gap_and_never_exceed_the_cap():
    rows = [row(str(i), f=20.0, model=20.0, r_sum=20.0) for i in range(30)]
    f = next(x for x in ins.build(evidence(recent_rows=rows), TH)[0] if x["kind"] == "FORECAST_VS_RECENT")
    assert len(f["proposal"]["items"]) == TH.max_proposals
    steady = evidence(recent_rows=[row("a", f=20.0, model=20.0, r_sum=76.0), row("b", f=2.0, model=2.0, r_sum=2.0)])
    f2 = [x for x in ins.build(steady, TH)[0] if x["kind"] == "FORECAST_VS_RECENT"]
    assert not f2 or not f2[0]["proposal"]


def test_variance_needs_closed_weeks_otherwise_it_is_reported_unavailable_not_zero():
    findings, unavailable = ins.build(evidence(), TH)
    assert not [f for f in findings if f["kind"] == "VARIANCE"] and any(u["analysis"] == "Plan versus actual variance" for u in unavailable)
    rows = [{"gkey": "Cricket / Bat", "dim": "category", "with_actual": 3, "lines": 10, "forecast_variance": -40.0, "f_a": 100.0, "a_f": 60.0}]
    f = next(x for x in ins.build(evidence(variance_rows=rows), TH)[0] if x["kind"] == "VARIANCE")
    assert "-40%" in f["statement"] and "actual - forecast" in " ".join(f["facts"])


def test_store_below_forecast_requires_repeated_shortfalls_and_is_not_made_for_live_online_data():
    demo = {"id": "p1", "name": "D", "status": "DRAFT", "source": "POC_SYNTHETIC", "version": 1}
    weeks = [{"location_id": "S1", "week_start": f"2026-09-0{i}", "a": 70.0, "f": 100.0} for i in range(1, 5)] + [{"location_id": "S2", "week_start": "2026-09-01", "a": 50.0, "f": 100.0}]
    f = [x for x in ins.build(evidence(plan=demo, store_weeks=weeks, history_weeks=104), TH)[0] if x["kind"] == "BELOW_FORECAST"]
    assert [x["scope"] for x in f] == [{"store": "S1"}]
    assert not [x for x in ins.build(evidence(store_weeks=weeks), TH)[0] if x["kind"] == "BELOW_FORECAST"]


def test_trend_needs_eight_closed_weeks():
    rows = [{"category_label": "Cricket / Ball", "last": 120.0, "prior": 60.0}]
    assert any(u["analysis"] == "Category growth or decline" for u in ins.build(evidence(trend_rows=rows), TH)[1])
    f = next(x for x in ins.build(evidence(trend_rows=rows, history_weeks=12), TH)[0] if x["kind"] == "TREND")
    assert "+100%" in f["statement"] and f["confidence"] == "MEDIUM"


def test_stock_risk_uses_the_plans_allocation_and_is_unavailable_until_one_exists():
    assert any(u["analysis"] == "Stock-out and excess risk" for u in ins.build(evidence(), TH)[1])
    alloc = {"generated": True, "high_risk": 2, "with_surplus": 1, "units_to_order": 50, "transfers": 1, "transfers_avoiding_orders": 8,
             "top_risks": [{"name": "Bat A", "location_id": "S1", "on_hand": 2, "incoming": 0, "shortage": 14, "coverage_days": 3}]}
    findings, _ = ins.build(evidence(alloc=alloc), TH)
    f = next(x for x in findings if x["kind"] == "STOCK_RISK")
    assert f["severity"] == "HIGH" and "14" in f["statement"] and "draft" in " ".join(f["risks"]).lower()
    assert next(x for x in findings if x["kind"] == "EXCESS_STOCK")["statement"].count("8") >= 1


def test_findings_are_stable_sorted_by_severity_and_do_not_mutate_the_evidence():
    ev = evidence(recent_rows=[row("a", f=14.0, r_sum=24.0)])
    before = copy.deepcopy(ev)
    a, _ = ins.build(ev, TH)
    b, _ = ins.build(copy.deepcopy(before), TH)
    assert [x["id"] for x in a] == [x["id"] for x in b]
    order = [ins.SEVERITY_ORDER[x["severity"]] for x in a]
    assert order == sorted(order)
    assert {k: v for k, v in ev.items() if k != "recent_rows"} == {k: v for k, v in before.items() if k != "recent_rows"}


def test_no_finding_ever_contains_an_instruction_to_order_approve_or_publish():
    ev = evidence(recent_rows=[row("a", f=14.0, r_sum=24.0)], alloc={"generated": True, "high_risk": 1, "with_surplus": 0, "units_to_order": 5, "transfers": 0, "transfers_avoiding_orders": 0,
                                                                         "top_risks": [{"name": "Bat A", "location_id": "S1", "on_hand": 0, "incoming": 0, "shortage": 9, "coverage_days": 0}]})
    for f in ins.build(ev, TH)[0]:
        text = (f["statement"] + " " + f["next_action"]).lower()
        assert "place an order" not in text and "approve the plan" not in text and "publish" not in text
