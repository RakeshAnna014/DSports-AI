"""Staff-side support AI API. Needs a verified token with SUPPORT_EXECUTIVE or ADMIN; the role check here (and again in the Java backend on every
operation) is the enforcement, the frontend only hides links. Nothing here changes a ticket: actions run through the backend with the staff member's own token."""
from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from app.auth.jwt import Principal, current_principal
from app.engineering.analysis import build_clusters
from app.engineering.incidents import reference_of
from app.engineering.repository import LogFilter
from app.engineering.safe import safe_text
from app.errors import ApiException
from app.obs import metrics
from app.support import staff_assist as sa
from app.support.staff_insights import build_insights, previous_period
from app.tools.dsports_client import ToolError

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/support", tags=["support-staff-ai"])
FEEDBACK = ("HELPFUL", "NOT_HELPFUL", "WANTED_PERSON")


async def staff(request: Request, principal: Principal = Depends(current_principal)) -> Principal:
    if not sa.is_staff(principal):
        metrics.GUARD_BLOCKS.labels("support_staff", "forbidden").inc()
        raise ApiException(403, "FORBIDDEN", "Support staff assistance is restricted to support staff")
    return principal


def _pool(request: Request):
    store = getattr(request.app.state.services, "store", None)
    return None if store is None else store.pool


class ActionRecord(BaseModel):
    model_config = ConfigDict(extra="forbid")
    state: str = Field(pattern="^(ATTEMPTED|SUCCEEDED|FAILED)$")
    detail: dict[str, Any] | None = None


class Feedback(BaseModel):
    model_config = ConfigDict(extra="forbid")
    conversationId: str = Field(min_length=8, max_length=64)
    outcome: str = Field(pattern="^(HELPFUL|NOT_HELPFUL|WANTED_PERSON)$")


@router.post("/staff/cases/{ref}/assist")
async def assist(ref: str, request: Request, principal: Principal = Depends(staff)) -> dict[str, Any]:
    return await sa.build_plan(request.app.state.services, principal, ref, _pool(request))


@router.post("/staff/cases/{ref}/actions/{action_id}")
async def record(ref: str, action_id: str, body: ActionRecord, request: Request, principal: Principal = Depends(staff)) -> dict[str, Any]:
    pool = _pool(request)
    if pool is None:
        raise ApiException(503, "SUPPORT_AI_UNAVAILABLE", "The AI database is not configured, so actions cannot be recorded")
    return {"history": await sa.record_action(pool, principal, ref, action_id, body.state, body.detail)}


@router.get("/staff/cases/{ref}/actions")
async def actions(ref: str, request: Request, principal: Principal = Depends(staff)) -> dict[str, Any]:
    pool = _pool(request)
    clean = sa.normalize_case_ref(ref)
    if clean is None:
        raise ApiException(400, "VALIDATION_ERROR", "That is not a valid case number")
    return {"history": await sa.history(pool, clean) if pool is not None else []}


@router.post("/feedback", status_code=204)
async def feedback(body: Feedback, request: Request, principal: Principal = Depends(current_principal)) -> None:
    """The customer's own answer to 'did this help?'. Recorded for the staff insights; it never changes a ticket."""
    await request.app.state.services.state.audit(customer_hash=principal.customer_hash, action="SUPPORT_FEEDBACK", outcome=body.outcome,
                                                 conversation_id=body.conversationId, detail=None)


async def _chatbot_stats(pool, first: date, last: date, backend: dict[str, Any] | None) -> dict[str, Any]:
    out: dict[str, Any] = {"feedback": {}, "feedback_total": 0, "support_conversations": 0, "linked_conversations": 0}
    if backend:
        out["created_by_assistant"], out["created_by_web"] = backend.get("createdByAssistant"), backend.get("createdByWeb")
    if pool is None:
        return out
    start, end = first, last + timedelta(days=1)
    rows = await pool.fetch("SELECT outcome, count(*) AS n FROM audit WHERE action = 'SUPPORT_FEEDBACK' AND ts >= $1::date AND ts < $2::date GROUP BY outcome", start, end)
    out["feedback"] = {r["outcome"]: int(r["n"]) for r in rows}
    out["feedback_total"] = sum(out["feedback"].values())
    out["support_conversations"] = int(await pool.fetchval("SELECT count(DISTINCT conversation_id) FROM audit WHERE action = 'SUPPORT_TURN' AND ts >= $1::date AND ts < $2::date", start, end) or 0)
    out["linked_conversations"] = int(await pool.fetchval("SELECT count(*) FROM conversations WHERE state->'slots'->>'case_ref' IS NOT NULL AND created_at >= $1::date AND created_at < $2::date", start, end) or 0)
    return out


@router.get("/staff/insights")
async def insights(request: Request, principal: Principal = Depends(staff), range: Annotated[str, Query(pattern="^(today|yesterday|last7|last30|custom)$")] = "last7",
                   from_: Annotated[date | None, Query(alias="from")] = None, to: date | None = None) -> dict[str, Any]:
    services = request.app.state.services
    params: dict[str, str] = {"range": range}
    if range == "custom":
        if from_ is None or to is None:
            raise ApiException(400, "VALIDATION_ERROR", "A custom range needs from and to")
        params |= {"from": from_.isoformat(), "to": to.isoformat()}
    try:
        current = await services.dsports.get("/api/v1/support/staff/report", token=principal.token, params=params)
        first, last = date.fromisoformat(current["from"]), date.fromisoformat(current["to"])
        pf, pl = previous_period(first, last)
        previous = await services.dsports.get("/api/v1/support/staff/report", token=principal.token, params={"range": "custom", "from": pf.isoformat(), "to": pl.isoformat()})
        analytics = await services.dsports.get("/api/v1/support/staff/analytics", token=principal.token)
    except ToolError as e:
        raise ApiException(502 if e.code.startswith("UPSTREAM") else 400, e.code, e.message) from None
    groups = None
    repo = getattr(services, "engineering", None)
    if repo is not None and "ADMIN" in principal.roles:
        try:
            from datetime import datetime, timezone
            rows = await repo.clusters(LogFilter(datetime.combine(first, datetime.min.time(), timezone.utc), datetime.combine(last + timedelta(days=1), datetime.min.time(), timezone.utc),
                                                 ("backend", "ai-service"), "ERROR", "real"), 3)
            groups = [{"reference": reference_of(c.signature), "title": safe_text(c.text, 120)[0], "category": c.category, "count": c.count} for c in build_clusters(rows)[:2]]
        except Exception:  # noqa: BLE001 - the engineering correlation is optional
            log.info("engineering correlation unavailable for insights")
    stats = await _chatbot_stats(_pool(request), first, last, analytics)
    return build_insights(current, previous, stats, groups)
