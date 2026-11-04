from __future__ import annotations

import pytest

from app.sourcing import workflow as w

BUYER, APPROVER, CUSTOMER = ["PROCUREMENT_BUYER"], ["CATEGORY_MANAGER"], ["CUSTOMER"]


def test_every_status_has_transitions_and_targets_exist():
    assert set(w.TRANSITIONS) == set(w.STATUSES)
    for src, targets in w.TRANSITIONS.items():
        assert all(t in w.STATUSES and t != src for t in targets)


def test_it_is_not_a_linear_pipeline():
    w.check_transition("UNDER_REVIEW", "NEEDS_MORE_EVIDENCE", BUYER, "unclear specs")
    w.check_transition("QUOTE_COMPARISON", "SUPPLIER_RESEARCH", BUYER, None)         # back to research
    w.check_transition("DISCOVERED", "REJECTED", BUYER, "not our segment")


def test_skipping_steps_is_refused():
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("DISCOVERED", "APPROVED_FOR_ONBOARDING", APPROVER, None)
    assert e.value.code == "INVALID_TRANSITION"


def test_customers_cannot_change_status():
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("DISCOVERED", "UNDER_REVIEW", CUSTOMER, None)
    assert e.value.status == 403


def test_reason_is_required_for_reject_hold_and_more_evidence():
    for target, frm in (("REJECTED", "DISCOVERED"), ("ON_HOLD", "DISCOVERED"), ("NEEDS_MORE_EVIDENCE", "UNDER_REVIEW")):
        with pytest.raises(w.WorkflowError) as e:
            w.check_transition(frm, target, BUYER, "  ")
        assert e.value.code == "REASON_REQUIRED"


def test_approval_needs_an_approver_a_quote_and_a_calculation():
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", BUYER, None, active_quotes=2, has_complete_calc=True)
    assert e.value.status == 403                                                       # a plain buyer cannot approve
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", APPROVER, None, active_quotes=0, has_complete_calc=True)
    assert e.value.code == "MISSING_EVIDENCE"
    with pytest.raises(w.WorkflowError):
        w.check_transition("QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", APPROVER, None, active_quotes=1, has_complete_calc=False)
    w.check_transition("QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", APPROVER, None, active_quotes=1, has_complete_calc=True)


def test_reopening_a_rejection_or_backing_out_of_approval_is_senior_only():
    with pytest.raises(w.WorkflowError):
        w.check_transition("REJECTED", "UNDER_REVIEW", BUYER, None)
    w.check_transition("REJECTED", "UNDER_REVIEW", APPROVER, None)
    with pytest.raises(w.WorkflowError):
        w.check_transition("APPROVED_FOR_ONBOARDING", "ON_HOLD", BUYER, "x")


def test_allowed_next_reflects_the_role():
    assert "APPROVED_FOR_ONBOARDING" not in w.allowed_next("QUOTE_COMPARISON", BUYER)
    assert "APPROVED_FOR_ONBOARDING" in w.allowed_next("QUOTE_COMPARISON", APPROVER)
    assert w.allowed_next("REJECTED", BUYER) == []


# ------------------------------------------------------------------------------------------------ business approval is a separate stage; demand gaps are not products
def test_approving_the_opportunity_is_a_separate_senior_decision_that_needs_identity_and_evidence():
    w.check_transition("SHORTLISTED", "APPROVED_OPPORTUNITY", APPROVER, None, has_identity=True, has_evidence=True)
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("SHORTLISTED", "APPROVED_OPPORTUNITY", BUYER, None)
    assert e.value.status == 403
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("SHORTLISTED", "APPROVED_OPPORTUNITY", APPROVER, None, has_identity=False)
    assert e.value.code == "IDENTITY_MISSING"
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("SHORTLISTED", "APPROVED_OPPORTUNITY", APPROVER, None, has_evidence=False)
    assert e.value.code == "MISSING_EVIDENCE"


def test_business_approval_does_not_imply_onboarding_approval():
    assert "APPROVED_FOR_ONBOARDING" not in w.TRANSITIONS["APPROVED_OPPORTUNITY"]
    assert w.TRANSITIONS["APPROVED_OPPORTUNITY"][0] == "SUPPLIER_RESEARCH"


def test_a_demand_gap_can_be_reviewed_held_or_dismissed_but_never_approved():
    for target in ("APPROVED_OPPORTUNITY", "APPROVED_FOR_ONBOARDING", "SHORTLISTED", "SUPPLIER_RESEARCH"):
        with pytest.raises(w.WorkflowError) as e:
            w.check_transition("UNDER_REVIEW" if target != "APPROVED_FOR_ONBOARDING" else "QUOTE_COMPARISON", target, APPROVER, None, record_kind="DEMAND_GAP", active_quotes=1, has_complete_calc=True)
        assert e.value.code in ("NOT_A_PRODUCT", "INVALID_TRANSITION")
    w.check_transition("DISCOVERED", "UNDER_REVIEW", BUYER, None, record_kind="DEMAND_GAP")
    w.check_transition("UNDER_REVIEW", "REJECTED", BUYER, "dismissed: generic term", record_kind="DEMAND_GAP")
    assert set(w.allowed_next("UNDER_REVIEW", BUYER, "DEMAND_GAP")) <= w.GAP_TARGETS


def test_a_record_of_unknown_provenance_cannot_be_approved_until_reviewed():
    with pytest.raises(w.WorkflowError) as e:
        w.check_transition("SHORTLISTED", "APPROVED_OPPORTUNITY", APPROVER, None, provenance="UNKNOWN_PROVENANCE")
    assert e.value.code == "PROVENANCE_UNKNOWN"