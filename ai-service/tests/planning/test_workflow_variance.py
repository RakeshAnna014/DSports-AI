from __future__ import annotations

import pytest

from app.planning import variance as v
from app.planning import workflow as w

PLANNER, STORE, REGIONAL, MANAGER, ADMIN, CUSTOMER = ["SALES_PLANNER"], ["STORE_MANAGER"], ["REGIONAL_MANAGER"], ["CATEGORY_MANAGER"], ["ADMIN"], ["CUSTOMER"]


def go(current, target, roles, actor="u2", submitted_by="u1", reason=None, self_ok=False):
    w.check_transition(current, target, roles, actor=actor, submitted_by=submitted_by, reason=reason, allow_self_approval=self_ok)


# ------------------------------------------------------------------------------------------------ workflow
def test_every_state_has_transitions_and_every_target_exists():
    assert set(w.TRANSITIONS) == set(w.STATUSES)
    for src, targets in w.TRANSITIONS.items():
        assert all(t in w.STATUSES and t != src for t in targets)


def test_the_happy_path_needs_a_planner_to_submit_and_a_different_approver():
    go("DRAFT", "SUBMITTED", PLANNER, actor="u1", submitted_by=None)
    go("SUBMITTED", "UNDER_REVIEW", MANAGER)
    go("UNDER_REVIEW", "APPROVED", MANAGER)
    go("APPROVED", "PUBLISHED", MANAGER)


def test_a_planner_cannot_review_approve_reject_return_or_publish():
    for target, cur in (("UNDER_REVIEW", "SUBMITTED"), ("APPROVED", "UNDER_REVIEW"), ("PUBLISHED", "APPROVED"), ("REJECTED", "SUBMITTED"), ("RETURNED", "SUBMITTED")):
        with pytest.raises(w.PlanError) as e:
            go(cur, target, PLANNER, reason="because")
        assert e.value.status == 403


def test_a_store_manager_edits_and_submits_but_does_not_approve_and_a_customer_does_nothing():
    go("DRAFT", "SUBMITTED", STORE, actor="u1", submitted_by=None)
    with pytest.raises(w.PlanError):
        go("UNDER_REVIEW", "APPROVED", STORE)
    with pytest.raises(w.PlanError):
        go("DRAFT", "SUBMITTED", CUSTOMER, actor="u1", submitted_by=None)
    with pytest.raises(w.PlanError):
        w.can_edit("DRAFT", CUSTOMER)


def test_a_regional_manager_can_approve_within_the_workflow_scope_checks_are_in_the_service():
    go("UNDER_REVIEW", "APPROVED", REGIONAL)


def test_the_submitter_cannot_approve_their_own_plan_unless_the_setting_allows_it():
    with pytest.raises(w.PlanError) as e:
        go("UNDER_REVIEW", "APPROVED", MANAGER, actor="u1", submitted_by="u1")
    assert e.value.code == "SELF_APPROVAL"
    go("UNDER_REVIEW", "APPROVED", MANAGER, actor="u1", submitted_by="u1", self_ok=True)


def test_skipping_states_is_refused():
    for cur, tgt in (("DRAFT", "APPROVED"), ("DRAFT", "PUBLISHED"), ("SUBMITTED", "APPROVED"), ("REJECTED", "DRAFT"), ("PUBLISHED", "APPROVED"), ("SUPERSEDED", "PUBLISHED")):
        with pytest.raises(w.PlanError) as e:
            go(cur, tgt, ADMIN, actor="u9")
        assert e.value.code == "INVALID_TRANSITION"


def test_returning_and_rejecting_need_a_reason():
    for tgt in ("RETURNED", "REJECTED"):
        with pytest.raises(w.PlanError) as e:
            go("UNDER_REVIEW", tgt, MANAGER, reason="  ")
        assert e.value.code == "REASON_REQUIRED"
        go("UNDER_REVIEW", tgt, MANAGER, reason="numbers do not add up")


def test_only_the_submitter_or_an_admin_can_withdraw():
    go("SUBMITTED", "DRAFT", PLANNER, actor="u1", submitted_by="u1")
    with pytest.raises(w.PlanError):
        go("SUBMITTED", "DRAFT", PLANNER, actor="u7", submitted_by="u1")
    go("SUBMITTED", "DRAFT", ADMIN, actor="u7", submitted_by="u1")


def test_only_draft_and_returned_plans_are_editable_and_approved_ones_need_a_revision():
    w.can_edit("DRAFT", PLANNER)
    w.can_edit("RETURNED", PLANNER)
    for s in ("SUBMITTED", "UNDER_REVIEW", "APPROVED", "PUBLISHED", "REJECTED", "SUPERSEDED"):
        with pytest.raises(w.PlanError) as e:
            w.can_edit(s, ADMIN)
        assert e.value.code == "PLAN_FROZEN"


def test_allowed_next_reflects_the_role_and_the_submitter():
    assert w.allowed_next("DRAFT", PLANNER, actor="u1") == ["SUBMITTED"]
    assert "APPROVED" not in w.allowed_next("UNDER_REVIEW", PLANNER, actor="u1")
    assert "APPROVED" in w.allowed_next("UNDER_REVIEW", MANAGER, actor="u2", submitted_by="u1")
    assert "APPROVED" not in w.allowed_next("UNDER_REVIEW", MANAGER, actor="u1", submitted_by="u1")
    assert w.allowed_next("REJECTED", ADMIN) == []


def test_scope_rules():
    assert w.sees_everything(ADMIN) and w.sees_everything(MANAGER) and w.sees_everything(PLANNER)
    assert not w.sees_everything(STORE) and not w.sees_everything(REGIONAL)
    assert w.sees_everything(STORE + ADMIN)


# ------------------------------------------------------------------------------------------------ variance formulas
def test_the_four_formulas():
    c = v.compare(actual=92, forecast=105, target=115)
    assert c["forecast_variance"] == -13 and c["forecast_variance_pct"] == -12.4
    assert c["target_variance"] == -23 and c["target_attainment_pct"] == 80.0


def test_a_zero_or_missing_target_or_forecast_never_divides_by_zero_or_invents_a_percentage():
    assert v.compare(10, 0, 0)["forecast_variance_pct"] is None and v.compare(10, 0, 0)["target_attainment_pct"] is None
    c = v.compare(10, None, None)
    assert c["forecast_variance"] is None and c["target_variance"] is None and c["target_attainment_pct"] is None
    assert v.compare(0, 5, 5)["target_attainment_pct"] == 0.0             # sold nothing against a real target: 0%, which is true


def test_model_forecast_manager_forecast_approved_forecast_target_and_actual_are_different_numbers():
    a = v.compare(92, 105, 115)               # approved forecast 105, target 115, actual 92: three distinct comparisons from five distinct values
    b = v.compare(92, 100, 115)               # against the MODEL forecast 100
    assert a["forecast_variance"] != b["forecast_variance"] and a["target_variance"] == b["target_variance"]


def test_rollups_do_not_treat_a_missing_target_as_zero_and_say_how_many_rows_had_each_value():
    rows = [{"actual": 10, "forecast": 8, "target": 12}, {"actual": 5, "forecast": 6, "target": None}, {"actual": None, "forecast": 4, "target": 4}]
    r = v.rollup(rows)
    assert r["actual"] == 15 and r["forecast"] == 18 and r["target"] == 16
    assert r["target_variance"] == -2 and r["target_attainment_pct"] == round(10 / 12 * 100, 1)       # only the row that has BOTH an actual and a target
    assert r["coverage"] == {"rows": 3, "with_actual": 2, "with_forecast": 3, "with_target": 2}


def test_significance_needs_both_a_percentage_and_a_unit_size():
    assert not v.is_significant(-1, 2, 20, 10)           # 50% but 1 unit
    assert not v.is_significant(-12, 100, 20, 10)        # 12 units but 12%
    assert v.is_significant(-30, 100, 20, 10)
    assert v.is_significant(15, 0, 20, 10)               # sold 15 where 0 was forecast
    assert not v.is_significant(None, 5, 20, 10)


def inv(**o):
    base = dict(actual=60.0, forecast=100.0, target=110.0, week_closed=True, stockout_days=None, returned_units=None, cancelled_units=None, gross_units=None, history_weeks=104, confidence="HIGH",
                recent_bias_pct=None, adjustments=[], events=[], pct_threshold=20, min_units=10)
    base.update(o)
    return v.investigate(**base)


def test_an_open_week_draws_no_conclusion():
    r = inv(week_closed=False)
    assert not r["flagged"] and "not closed" in r["facts"][0] and r["hypotheses"] == []


def test_missing_actual_is_missing_data_not_zero_sales():
    r = inv(actual=None)
    assert not r["flagged"] and "missing data, not zero sales" in r["facts"][0]


def test_facts_come_from_records_and_hypotheses_are_separate_and_hedged():
    r = inv(stockout_days=3, returned_units=4, gross_units=104, cancelled_units=2, events=["a regional tournament"], recent_bias_pct=22.0)
    assert r["flagged"]
    assert any("3 stock-out day" in f for f in r["facts"]) and any("returned" in f for f in r["facts"]) and any("tournament" in f for f in r["facts"])
    assert any("rather than lower demand" in h for h in r["hypotheses"]) and any("may have changed demand, but the data does not show that it did" in h for h in r["hypotheses"])
    assert not any("caused" in t.lower() for t in r["facts"] + r["hypotheses"])
    assert any("promotion" in x.lower() for x in r["limits"])


def test_short_history_and_low_confidence_are_reported_as_forecast_weakness():
    r = inv(history_weeks=10, confidence="LOW")
    assert any("only 10 week" in f for f in r["facts"]) and any("forecast error" in h for h in r["hypotheses"])


def test_an_unexplained_gap_says_so_instead_of_inventing_a_reason():
    r = inv()
    assert r["flagged"] and any("do not explain" in h for h in r["hypotheses"])


def test_an_approved_correction_is_disclosed():
    r = inv(adjustments=[{"delta_units": -3, "reason_code": "DUPLICATE_ORDER"}])
    assert any("approved correction of -3" in f for f in r["facts"])
