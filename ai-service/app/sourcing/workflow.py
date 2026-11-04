"""Buyer workflow rules: roles, allowed status transitions, and what each one requires. Pure and testable.

Stages are deliberately separate (a buyer can decide one without implying the next):
    discovery            DISCOVERED, UNDER_REVIEW, SHORTLISTED
    business decision    APPROVED_OPPORTUNITY      "this product is worth pursuing" (needs a real product record with an identity and source evidence)
    supplier work        SUPPLIER_RESEARCH, QUOTE_COMPARISON
    onboarding prep      APPROVED_FOR_ONBOARDING   "prepare sourcing and onboarding" (needs an active entered/verified quotation and a saved margin calculation)
Catalogue READINESS is not a status at all: it is validated separately (app.sourcing.readiness) and gates the proposal, not the decision.
A customer-demand gap is a search term, not a product: it can be reviewed, held or dismissed, but never approved until a real product is found for it.
Approval is a recorded decision. It is NOT authority to purchase, to publish a product or to change a price: nothing in this module can do any of those things.
"""
from __future__ import annotations

from typing import Iterable

STATUSES = ("DISCOVERED", "UNDER_REVIEW", "SHORTLISTED", "APPROVED_OPPORTUNITY", "SUPPLIER_RESEARCH", "QUOTE_COMPARISON", "APPROVED_FOR_ONBOARDING", "REJECTED", "ON_HOLD", "NEEDS_MORE_EVIDENCE")
BUYER_ROLES = {"ADMIN", "ROLE_ADMIN", "PROCUREMENT_BUYER", "ROLE_PROCUREMENT_BUYER", "CATEGORY_MANAGER", "ROLE_CATEGORY_MANAGER"}
APPROVER_ROLES = {"ADMIN", "ROLE_ADMIN", "CATEGORY_MANAGER", "ROLE_CATEGORY_MANAGER"}

TRANSITIONS: dict[str, tuple[str, ...]] = {
    "DISCOVERED": ("UNDER_REVIEW", "REJECTED", "ON_HOLD"),
    "UNDER_REVIEW": ("SHORTLISTED", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"),
    "SHORTLISTED": ("APPROVED_OPPORTUNITY", "SUPPLIER_RESEARCH", "UNDER_REVIEW", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"),
    "APPROVED_OPPORTUNITY": ("SUPPLIER_RESEARCH", "UNDER_REVIEW", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"),
    "SUPPLIER_RESEARCH": ("QUOTE_COMPARISON", "SHORTLISTED", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"),
    "QUOTE_COMPARISON": ("APPROVED_FOR_ONBOARDING", "SUPPLIER_RESEARCH", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"),
    "NEEDS_MORE_EVIDENCE": ("UNDER_REVIEW", "SUPPLIER_RESEARCH", "REJECTED", "ON_HOLD"),
    "ON_HOLD": ("UNDER_REVIEW", "REJECTED"),
    "REJECTED": ("UNDER_REVIEW",),
    "APPROVED_FOR_ONBOARDING": ("ON_HOLD", "REJECTED"),
}
GAP_TARGETS = {"UNDER_REVIEW", "NEEDS_MORE_EVIDENCE", "REJECTED", "ON_HOLD"}       # what a demand gap may become (REJECTED = dismissed)
REASON_REQUIRED = {"REJECTED", "ON_HOLD", "NEEDS_MORE_EVIDENCE"}
APPROVER_ONLY_TARGETS = {"APPROVED_OPPORTUNITY", "APPROVED_FOR_ONBOARDING"}
APPROVER_ONLY_FROM = {"REJECTED", "APPROVED_FOR_ONBOARDING", "APPROVED_OPPORTUNITY"}      # reopening a rejection or backing out of an approval is a senior decision


class WorkflowError(Exception):
    def __init__(self, code: str, message: str, status: int = 409):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


def is_buyer(roles: Iterable[str]) -> bool:
    return bool(BUYER_ROLES & set(roles))


def is_approver(roles: Iterable[str]) -> bool:
    return bool(APPROVER_ROLES & set(roles))


def allowed_next(current: str, roles: Iterable[str], record_kind: str = "PRODUCT_CANDIDATE") -> list[str]:
    roles = set(roles)
    out = []
    for target in TRANSITIONS.get(current, ()):
        if target in APPROVER_ONLY_TARGETS and not is_approver(roles):
            continue
        if current in APPROVER_ONLY_FROM and not is_approver(roles):
            continue
        if record_kind == "DEMAND_GAP" and target not in GAP_TARGETS:
            continue
        out.append(target)
    return out


def check_transition(current: str, target: str, roles: Iterable[str], reason: str | None, *, active_quotes: int = 0, has_complete_calc: bool = False,
                     record_kind: str = "PRODUCT_CANDIDATE", provenance: str | None = None, has_identity: bool = True, has_evidence: bool = True) -> None:
    """Raises WorkflowError (with an HTTP status) when the change is not allowed."""
    if target not in STATUSES:
        raise WorkflowError("INVALID_STATUS", f"Unknown status {target!r}", 422)
    if not is_buyer(roles):
        raise WorkflowError("FORBIDDEN", "Only buyers can change an opportunity's status", 403)
    if target not in TRANSITIONS.get(current, ()):
        raise WorkflowError("INVALID_TRANSITION", f"An opportunity cannot move from {current} to {target}")
    if (target in APPROVER_ONLY_TARGETS or current in APPROVER_ONLY_FROM) and not is_approver(roles):
        raise WorkflowError("FORBIDDEN", "Only an approver (category manager or administrator) can make this decision", 403)
    if target in REASON_REQUIRED and not (reason or "").strip():
        raise WorkflowError("REASON_REQUIRED", f"A reason is required to move an opportunity to {target}", 422)
    if record_kind == "DEMAND_GAP" and target not in GAP_TARGETS:
        raise WorkflowError("NOT_A_PRODUCT", "A customer-demand gap is a search term, not a product: find or link a real product before taking it further. It can be reviewed, held or dismissed.")
    if target in ("APPROVED_OPPORTUNITY", "APPROVED_FOR_ONBOARDING") and provenance == "UNKNOWN_PROVENANCE":
        raise WorkflowError("PROVENANCE_UNKNOWN", "Where this record came from is unknown. Classify its provenance (review it) before approving it.")
    if target == "APPROVED_OPPORTUNITY":
        if not has_identity:
            raise WorkflowError("IDENTITY_MISSING", "Approving the opportunity needs a product identity: a brand and a model number, SKU or GTIN")
        if not has_evidence:
            raise WorkflowError("MISSING_EVIDENCE", "Approving the opportunity needs source evidence (a source URL or a buyer note)")
    if target == "APPROVED_FOR_ONBOARDING":
        if active_quotes < 1:
            raise WorkflowError("MISSING_EVIDENCE", "Approval needs at least one active supplier quotation that a buyer entered or verified")
        if not has_complete_calc:
            raise WorkflowError("MISSING_EVIDENCE", "Approval needs a saved landed-cost and margin calculation with a selling price")
