"""Who may do what in Sales & Inventory Planning, and which plan state transitions are legal. Pure functions: the API and the service both call them, and the frontend only mirrors them.

Roles (names are those the DSports identity service can issue; the AI service decides what they mean here):
  ADMIN                 everything, in every location
  CATEGORY_MANAGER      plans, approves and publishes, in every location
  SALES_PLANNER         builds and edits plans, submits them; cannot approve or publish
  REGIONAL_MANAGER      edits, reviews and approves within the regions/zones/stores granted to them; publishes within them
  STORE_MANAGER         edits targets and manager forecasts for the stores granted to them; submits; cannot approve

SCOPE: ADMIN, CATEGORY_MANAGER and SALES_PLANNER see every location. A REGIONAL_MANAGER or STORE_MANAGER sees and edits only the locations granted in `pln_scope` (and everything under a region or zone).

PLAN STATES:  DRAFT -> SUBMITTED -> UNDER_REVIEW -> APPROVED -> PUBLISHED
              SUBMITTED / UNDER_REVIEW -> RETURNED (back for revision; editable again) or REJECTED (final)
              SUBMITTED / UNDER_REVIEW -> DRAFT (the submitter withdraws it)
              APPROVED / PUBLISHED lines are frozen: changing them means creating a REVISION (a new plan, revision + 1), and the old one becomes SUPERSEDED when the new one is approved.
SEPARATION OF DUTIES: the person who submitted a plan cannot approve it (unless PLANNING_ALLOW_SELF_APPROVAL is set).
"""
from __future__ import annotations

from typing import Iterable

ADMIN_ROLES = {"ADMIN", "ROLE_ADMIN", "SUPER_ADMIN"}
ROLE_ALL_SCOPE = ADMIN_ROLES | {"CATEGORY_MANAGER", "ROLE_CATEGORY_MANAGER", "SALES_PLANNER", "ROLE_SALES_PLANNER"}
PLANNER_ROLES = ROLE_ALL_SCOPE | {"REGIONAL_MANAGER", "ROLE_REGIONAL_MANAGER", "STORE_MANAGER", "ROLE_STORE_MANAGER"}
APPROVER_ROLES = ADMIN_ROLES | {"CATEGORY_MANAGER", "ROLE_CATEGORY_MANAGER", "REGIONAL_MANAGER", "ROLE_REGIONAL_MANAGER"}
SCOPED_ROLES = {"REGIONAL_MANAGER", "ROLE_REGIONAL_MANAGER", "STORE_MANAGER", "ROLE_STORE_MANAGER"}
READ_ROLES = PLANNER_ROLES

STATUSES = ("DRAFT", "SUBMITTED", "UNDER_REVIEW", "APPROVED", "PUBLISHED", "REJECTED", "RETURNED", "SUPERSEDED")
EDITABLE = {"DRAFT", "RETURNED"}
FROZEN = {"APPROVED", "PUBLISHED", "SUPERSEDED", "REJECTED"}

TRANSITIONS: dict[str, tuple[str, ...]] = {
    "DRAFT": ("SUBMITTED",),
    "RETURNED": ("SUBMITTED",),
    "SUBMITTED": ("UNDER_REVIEW", "RETURNED", "REJECTED", "DRAFT"),
    "UNDER_REVIEW": ("APPROVED", "RETURNED", "REJECTED", "DRAFT"),
    "APPROVED": ("PUBLISHED", "SUPERSEDED"),
    "PUBLISHED": ("SUPERSEDED",),
    "REJECTED": (),
    "SUPERSEDED": (),
}
REASON_REQUIRED = {"RETURNED", "REJECTED"}
APPROVER_TARGETS = {"UNDER_REVIEW", "APPROVED", "RETURNED", "REJECTED", "PUBLISHED"}


class PlanError(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def has_any(roles: Iterable[str], allowed: set[str]) -> bool:
    return bool(set(roles) & allowed)


def is_planner(roles: Iterable[str]) -> bool:
    return has_any(roles, PLANNER_ROLES)


def is_approver(roles: Iterable[str]) -> bool:
    return has_any(roles, APPROVER_ROLES)


def sees_everything(roles: Iterable[str]) -> bool:
    """Roles that are not limited by `pln_scope`. A scoped role combined with an all-scope role (e.g. ADMIN) sees everything."""
    return has_any(roles, ROLE_ALL_SCOPE)


def check_transition(current: str, target: str, roles: Iterable[str], *, actor: str, submitted_by: str | None, reason: str | None, allow_self_approval: bool = False) -> None:
    roles = list(roles)
    if target not in TRANSITIONS.get(current, ()):
        raise PlanError("INVALID_TRANSITION", f"A plan cannot move from {current} to {target}", 409)
    if target in APPROVER_TARGETS and not is_approver(roles):
        raise PlanError("FORBIDDEN", "Only a regional manager, category manager or administrator can review, approve, return, reject or publish a plan", 403)
    if target in ("SUBMITTED", "DRAFT") and not is_planner(roles):
        raise PlanError("FORBIDDEN", "You cannot submit or withdraw a plan", 403)
    if target == "DRAFT" and submitted_by not in (None, actor) and not has_any(roles, ADMIN_ROLES):
        raise PlanError("FORBIDDEN", "Only the person who submitted a plan can withdraw it", 403)
    if target in REASON_REQUIRED and not (reason or "").strip():
        raise PlanError("REASON_REQUIRED", f"A reason is required to move a plan to {target}", 422)
    if target == "APPROVED" and not allow_self_approval and submitted_by is not None and submitted_by == actor:
        raise PlanError("SELF_APPROVAL", "You submitted this plan: someone else must approve it", 403)


def can_edit(status: str, roles: Iterable[str]) -> None:
    if status not in EDITABLE:
        raise PlanError("PLAN_FROZEN", f"A plan in status {status} cannot be edited: create a revision instead", 409)
    if not is_planner(roles):
        raise PlanError("FORBIDDEN", "You cannot edit plans", 403)


def allowed_next(status: str, roles: Iterable[str], *, actor: str = "", submitted_by: str | None = None, allow_self_approval: bool = False) -> list[str]:
    out = []
    for t in TRANSITIONS.get(status, ()):
        try:
            check_transition(status, t, roles, actor=actor, submitted_by=submitted_by, reason="x", allow_self_approval=allow_self_approval)
            out.append(t)
        except PlanError:
            continue
    return out


REASON_CODES = ("PROMOTION", "LOCAL_EVENT", "SUPPLY_CONSTRAINT", "COMPETITOR_ACTIVITY", "STORE_CLOSURE", "SPECIAL_ORDER", "DATA_CORRECTION", "OTHER")
ADJUSTMENT_REASONS = ("DATA_CORRECTION", "RETURNS_NOT_RECORDED", "DUPLICATE_ORDER", "MISSING_ORDERS", "WRONG_PRODUCT_MAPPING", "OTHER")
