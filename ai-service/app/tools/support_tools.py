"""Support tools: the customer's returns and support cases, the support policy search, and the four case WRITE tools.

Identity is the forwarded bearer token; NO tool takes a customer id. Every result is a whitelist built field by field. Staff operations
(assign, internal notes, status changes, resolution) are deliberately NOT tools: the assistant can never resolve, close or reassign a
case, and a customer-initiated escalation is only ever a REQUEST that a person has to accept.

WRITE tools (create_support_case, add_case_message, request_case_escalation, reopen_support_case) cannot run without the confirmation
service having authorised that exact call; each has a `prepare` step that checks live state and builds the summary the customer confirms.
"""
from __future__ import annotations

import re
import uuid
from typing import Literal

from pydantic import Field, field_validator

from app.guard.untrusted import sanitize_untrusted
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, Proposal, StrictArgs, Tool, ToolContext
from app.tools.shop_tools import KnowledgeArgs, get_product_knowledge

CASE_REF = re.compile(r"^(CASE-\d{8}-\d{6}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$")
Category = Literal["MISSING_DELIVERY", "DELAYED_DELIVERY", "DAMAGED_PRODUCT", "WRONG_PRODUCT", "PARTIAL_DELIVERY", "PAYMENT_ISSUE", "DUPLICATE_PAYMENT",
                   "REFUND_ISSUE", "RETURN_ISSUE", "EXCHANGE_REQUEST", "PRODUCT_COMPLAINT",
                   "ORDER_ISSUE", "TECHNICAL_ISSUE", "GENERAL"]
SUPPORT_DOC_TYPES = ["policy", "faq", "warranty", "procedure"]


def _clean(text: str | None, limit: int) -> str:
    return sanitize_untrusted(text or "", limit)[0]


def _canonical_ref(v: str) -> str:
    v = v.strip()
    return v.upper() if v.upper().startswith("CASE-") else v.lower()


def normalize_case_ref(raw: str | None) -> str | None:
    """A well-formed case number or id in canonical form, else None (also keeps odd characters out of the URL)."""
    return _canonical_ref(raw) if raw and CASE_REF.match(raw.strip()) else None


def _norm(text: str | None) -> str | None:
    """Collapse whitespace and control characters. Deterministic, so preparing twice gives identical arguments."""
    if text is None:
        return None
    out = " ".join(re.sub(r"[\x00-\x08\x0b-\x1f\x7f]", " ", text).split())
    return out or None


# ---------------------------------------------------------------------------------------------------- arguments

class NoArgs(StrictArgs):
    pass


class ReturnStatusArgs(StrictArgs):
    order_id: uuid.UUID | None = None


class CasesArgs(StrictArgs):
    unresolved_only: bool = False
    limit: int = Field(10, ge=1, le=20)


class CaseRefArgs(StrictArgs):
    case_ref: str | None = Field(None, max_length=40, description="Case number (CASE-YYYYMMDD-NNNNNN) or id; omit for the most recently updated case")

    @field_validator("case_ref")
    @classmethod
    def _shape(cls, v: str | None) -> str | None:
        if v is not None and not CASE_REF.match(v.strip()):
            raise ValueError("case_ref format")      # also keeps path traversal and odd characters out of the URL
        return _canonical_ref(v) if v else v


class PolicyArgs(StrictArgs):
    question: str = Field(min_length=3, max_length=300)
    limit: int | None = Field(None, ge=1, le=8)


class EvidenceArg(StrictArgs):
    kind: str = Field(max_length=30)
    ref: str | None = Field(None, max_length=100)
    summary: str = Field(min_length=1, max_length=400)


class CreateCaseArgs(StrictArgs):
    category: Category
    subject: str = Field(min_length=3, max_length=200)
    description: str = Field(min_length=3, max_length=2000)
    order_id: uuid.UUID | None = None
    product_id: uuid.UUID | None = None
    payment_id: uuid.UUID | None = None
    return_id: uuid.UUID | None = None
    safety_related: bool = False
    investigation_summary: str | None = Field(None, max_length=4000)
    evidence: list[EvidenceArg] = Field(default_factory=list, max_length=20)
    policy_citations: list[str] = Field(default_factory=list, max_length=10)
    missing_information: list[str] = Field(default_factory=list, max_length=10)
    resolution_recommendation: str | None = Field(None, max_length=1000)
    escalation_reason: str | None = Field(None, max_length=500)


class CaseMessageArgs(StrictArgs):
    case_ref: str = Field(max_length=40)
    message: str = Field(min_length=1, max_length=2000)

    @field_validator("case_ref")
    @classmethod
    def _shape(cls, v: str) -> str:
        if not CASE_REF.match(v.strip()):
            raise ValueError("case_ref format")
        return _canonical_ref(v)


class CaseReasonArgs(StrictArgs):
    case_ref: str = Field(max_length=40)
    reason: str = Field(min_length=3, max_length=500)

    @field_validator("case_ref")
    @classmethod
    def _shape(cls, v: str) -> str:
        if not CASE_REF.match(v.strip()):
            raise ValueError("case_ref format")
        return _canonical_ref(v)


# ---------------------------------------------------------------------------------------------------- views (whitelists)

def _escalation(c: dict) -> dict:
    e = c.get("escalation") or {}
    return {"state": e.get("state", "NONE"), "reason": _clean(e.get("reason"), 300) or None, "human_accepted": bool(e.get("humanAccepted"))}


def case_summary(c: dict) -> dict:
    return {"case_id": c.get("id"), "case_number": c.get("caseNumber"), "category": c.get("category"), "status": c.get("status"),
            "priority": c.get("priority"), "subject": _clean(c.get("subject"), 200), "order_number": c.get("orderNumber"),
            "escalation": _escalation(c), "created_at": c.get("createdAt"), "updated_at": c.get("updatedAt"), "resolved_at": c.get("resolvedAt")}


def case_view(c: dict) -> dict:
    out = case_summary(c)
    out.update({
        "description": _clean(c.get("description"), 600), "product_name": c.get("productName"),
        "order_status_at_creation": c.get("orderStatusAtCreation"), "shipment_status_at_creation": c.get("shipmentStatusAtCreation"),
        "resolution_outcome": c.get("resolutionOutcome"), "resolution_note": _clean(c.get("resolutionNote"), 400) or None,
        "assigned_to_support_team": bool(c.get("assignedToSupportTeam")), "can_reopen": bool(c.get("canReopen")),
        "reopen_window_days": c.get("reopenWindowDays"), "version": c.get("version"),
        "messages": [{"from": m.get("from"), "body": _clean(m.get("body"), 300), "created_at": m.get("createdAt")} for m in (c.get("messages") or [])][-5:],
        "timeline": [{"type": e.get("type"), "from_status": e.get("fromStatus"), "to_status": e.get("toStatus"), "detail": _clean(e.get("detail"), 200) or None,
                      "at": e.get("at")} for e in (c.get("timeline") or [])][-8:]})
    return out


# ---------------------------------------------------------------------------------------------------- READ tools

async def get_return_status(ctx: ToolContext, a: ReturnStatusArgs):
    rows = await ctx.dsports.get("/api/v1/returns", token=ctx.token) or []
    if a.order_id is not None:
        rows = [r for r in rows if r.get("orderId") == str(a.order_id)]
    rows = sorted(rows, key=lambda r: r.get("createdAt") or "", reverse=True)[:10]
    return {"returns": [{"return_id": r["id"], "order_id": r.get("orderId"), "product_id": r.get("productId"), "product_name": r.get("productName"),
                         "quantity": r.get("quantity"), "status": r.get("status"), "decision_note": _clean(r.get("decisionNote"), 200) or None,
                         "requested_at": r.get("createdAt"), "updated_at": r.get("updatedAt")} for r in rows], "count": len(rows)}


async def get_customer_support_cases(ctx: ToolContext, a: CasesArgs):
    rows = await ctx.dsports.get("/api/v1/support/cases", token=ctx.token, params={"unresolved": str(a.unresolved_only).lower()}) or []
    return {"cases": [case_summary(c) for c in rows[: a.limit]], "count": len(rows), "shown": min(len(rows), a.limit)}


async def get_support_case(ctx: ToolContext, a: CaseRefArgs):
    ref = a.case_ref
    if not ref:
        rows = await ctx.dsports.get("/api/v1/support/cases", token=ctx.token) or []
        if not rows:
            raise ToolError("NOT_FOUND", "You have no support cases yet")
        ref = rows[0]["id"]                                   # the API lists the most recently updated first
    try:
        c = await ctx.dsports.get(f"/api/v1/support/cases/{ref}", token=ctx.token)
    except ToolError as exc:
        if exc.code in ("NOT_FOUND", "CASE_NOT_FOUND", "FORBIDDEN"):
            raise ToolError("NOT_FOUND", "I could not find that case on your account") from None    # one answer for "missing" and "someone else's"
        raise
    return case_view(c)


async def search_support_policy(ctx: ToolContext, a: PolicyArgs):
    return await get_product_knowledge(ctx, KnowledgeArgs(question=a.question, document_types=SUPPORT_DOC_TYPES, limit=a.limit))


# ---------------------------------------------------------------------------------------------------- WRITE tools

def _evidence_json(items: list[EvidenceArg]) -> list[dict]:
    return [{"kind": _norm(e.kind) or "OTHER", "ref": _norm(e.ref), "summary": _norm(e.summary) or ""} for e in items]


async def _prepare_create_case(ctx: ToolContext, a: CreateCaseArgs) -> Proposal:
    order_number = None
    if a.order_id is not None:
        detail = await ctx.dsports.get(f"/api/v1/orders/{a.order_id}", token=ctx.token)       # DSports enforces ownership (403/404)
        order_number = detail.get("orderNumber")
        if a.product_id is not None and not any(i.get("productId") == str(a.product_id) for i in detail.get("items", [])):
            raise ToolError("NOT_IN_ORDER", "That product is not part of that order")
    elif a.product_id is not None:
        raise ToolError("INVALID_ARGUMENTS", "A product needs an order")
    subject, description = _norm(a.subject) or "", _norm(a.description) or ""
    if len(subject) < 3 or len(description) < 3:
        raise ToolError("INVALID_ARGUMENTS", "A subject and a description are needed")
    args = {"category": a.category, "subject": subject, "description": description, "order_id": str(a.order_id) if a.order_id else None,
            "product_id": str(a.product_id) if a.product_id else None, "payment_id": str(a.payment_id) if a.payment_id else None,
            "return_id": str(a.return_id) if a.return_id else None, "safety_related": a.safety_related,
            "investigation_summary": _norm(a.investigation_summary), "evidence": _evidence_json(a.evidence),
            "policy_citations": [_norm(c) or "" for c in a.policy_citations if _norm(c)], "missing_information": [_norm(m) or "" for m in a.missing_information if _norm(m)],
            "resolution_recommendation": _norm(a.resolution_recommendation), "escalation_reason": _norm(a.escalation_reason)}
    about = f" about order {order_number}" if order_number else ""
    human = " and ask for a person to review it" if args["escalation_reason"] else ""
    summary = f"Open a support case{about}: \"{subject}\" (category {a.category.replace('_', ' ').lower()}){human}"
    return Proposal(summary, args, {"order_number": order_number, "category": a.category, "subject": subject})


async def _create_case(ctx: ToolContext, a: CreateCaseArgs):
    body = {"category": a.category, "subject": _norm(a.subject), "description": _norm(a.description), "orderId": str(a.order_id) if a.order_id else None,
            "productId": str(a.product_id) if a.product_id else None, "paymentId": str(a.payment_id) if a.payment_id else None,
            "returnId": str(a.return_id) if a.return_id else None, "safetyRelated": a.safety_related,
            "investigationSummary": _norm(a.investigation_summary), "evidence": _evidence_json(a.evidence),
            "policyCitations": [_norm(c) for c in a.policy_citations if _norm(c)], "missingInformation": [_norm(m) for m in a.missing_information if _norm(m)],
            "resolutionRecommendation": _norm(a.resolution_recommendation), "escalationReason": _norm(a.escalation_reason), "source": "AI_ASSISTANT"}
    r = await ctx.dsports.post("/api/v1/support/cases", token=ctx.token, idempotency_key=ctx.idempotency_key, json={k: v for k, v in body.items() if v is not None})
    c = r["supportCase"]
    return {"outcome": r["outcome"], **case_view(c)}


async def _existing_case(ctx: ToolContext, ref: str) -> dict:
    try:
        return await ctx.dsports.get(f"/api/v1/support/cases/{ref}", token=ctx.token)
    except ToolError as exc:
        if exc.code in ("NOT_FOUND", "CASE_NOT_FOUND", "FORBIDDEN"):
            raise ToolError("NOT_FOUND", "I could not find that case on your account") from None
        raise


async def _prepare_message(ctx: ToolContext, a: CaseMessageArgs) -> Proposal:
    c = await _existing_case(ctx, a.case_ref)
    if c.get("status") == "RESOLVED":
        raise ToolError("CASE_RESOLVED", f"Case {c.get('caseNumber')} is resolved. You can reopen it, or raise a new case")
    message = _norm(a.message) or ""
    if not message:
        raise ToolError("INVALID_ARGUMENTS", "The message is empty")
    shown = message if len(message) <= 160 else message[:157] + "..."
    return Proposal(f"Add this message to case {c.get('caseNumber')}: \"{shown}\"", {"case_ref": c.get("caseNumber"), "message": message},
                    {"case_number": c.get("caseNumber")})


async def _add_message(ctx: ToolContext, a: CaseMessageArgs):
    r = await ctx.dsports.post(f"/api/v1/support/cases/{a.case_ref}/messages", token=ctx.token, json={"body": _norm(a.message)})
    return {"action": "message_added", **case_view(r)}


async def _prepare_escalate(ctx: ToolContext, a: CaseReasonArgs) -> Proposal:
    c = await _existing_case(ctx, a.case_ref)
    if c.get("status") == "RESOLVED":
        raise ToolError("CASE_RESOLVED", f"Case {c.get('caseNumber')} is resolved, so it cannot be escalated. You can reopen it")
    state = (c.get("escalation") or {}).get("state", "NONE")
    if state != "NONE":
        raise ToolError("ALREADY_ESCALATED", f"Human review was already requested for case {c.get('caseNumber')}"
                        + (" and a support executive has taken it" if state == "ACCEPTED" else ", and nobody has taken it yet"))
    return Proposal(f"Ask for a person to review case {c.get('caseNumber')} (this records your request; it does not mean anyone has taken the case yet)",
                    {"case_ref": c.get("caseNumber"), "reason": _norm(a.reason) or ""}, {"case_number": c.get("caseNumber")})


async def _escalate(ctx: ToolContext, a: CaseReasonArgs):
    r = await ctx.dsports.post(f"/api/v1/support/cases/{a.case_ref}/escalate", token=ctx.token, json={"reason": _norm(a.reason)})
    return {"action": "escalation_requested", **case_view(r)}


async def _prepare_reopen(ctx: ToolContext, a: CaseReasonArgs) -> Proposal:
    c = await _existing_case(ctx, a.case_ref)
    if c.get("status") != "RESOLVED":
        raise ToolError("NOT_RESOLVED", f"Case {c.get('caseNumber')} is not resolved, so there is nothing to reopen")
    if not c.get("canReopen"):
        raise ToolError("REOPEN_NOT_ALLOWED", f"Case {c.get('caseNumber')} can no longer be reopened (the window is {c.get('reopenWindowDays')} days). Please raise a new case")
    return Proposal(f"Reopen case {c.get('caseNumber')} because: \"{_norm(a.reason)}\"", {"case_ref": c.get("caseNumber"), "reason": _norm(a.reason) or ""},
                    {"case_number": c.get("caseNumber")})


async def _reopen(ctx: ToolContext, a: CaseReasonArgs):
    r = await ctx.dsports.post(f"/api/v1/support/cases/{a.case_ref}/reopen", token=ctx.token, json={"reason": _norm(a.reason)})
    return {"action": "case_reopened", **case_view(r)}


def tools() -> list[Tool]:
    return [
        Tool("get_return_status", "The customer's return requests and their status (decided by DSports), optionally for one order.", Kind.READ,
             ReturnStatusArgs, get_return_status),
        Tool("get_customer_support_cases", "The customer's own support cases (most recently updated first).", Kind.READ, CasesArgs, get_customer_support_cases),
        Tool("get_support_case", "One of the customer's support cases with its customer-visible messages and timeline (by case number, or the latest).", Kind.READ,
             CaseRefArgs, get_support_case),
        Tool("search_support_policy", "Search the shipping, returns, refunds, payment, warranty and support procedures and return cited passages.", Kind.READ,
             PolicyArgs, search_support_policy, requires_auth=False),
        Tool("create_support_case", "Open a persisted support case for the customer, with the investigation findings attached. Needs the customer's confirmation.",
             Kind.WRITE, CreateCaseArgs, _create_case, prepare=_prepare_create_case),
        Tool("add_case_message", "Add the customer's message to their own open support case. Needs the customer's confirmation.", Kind.WRITE, CaseMessageArgs,
             _add_message, prepare=_prepare_message),
        Tool("request_case_escalation", "Record the customer's request for a person to review their open case. It does not assign the case. Needs confirmation.",
             Kind.WRITE, CaseReasonArgs, _escalate, prepare=_prepare_escalate),
        Tool("reopen_support_case", "Reopen the customer's resolved case where the reopen window allows. Needs confirmation.", Kind.WRITE, CaseReasonArgs,
             _reopen, prepare=_prepare_reopen),
    ]
