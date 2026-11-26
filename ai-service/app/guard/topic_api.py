"""Staff view of the refused-message log: JSON or CSV. ADMIN only, enforced here (the role check runs before anything else is looked at). Read-only; nothing here can
change what the assistant answers. Rows carry topic, role, text and time and never an identity."""
from __future__ import annotations

import csv
import io
from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import Response

from app.auth.jwt import Principal, current_principal
from app.errors import ApiException
from app.guard.topic_log import MAX_LIMIT, RefusalLog

router = APIRouter(prefix="/v1/topic", tags=["topic-guardrails"])


async def admin(principal: Principal = Depends(current_principal)) -> Principal:
    if not principal.is_admin:
        raise ApiException(403, "FORBIDDEN", "The refused-message log is restricted to administrators")
    return principal


def refusal_log(request: Request, _: Principal = Depends(admin)) -> RefusalLog:
    log = getattr(request.app.state.services, "refusals", None)
    if log is None:
        raise ApiException(503, "REFUSAL_LOG_UNAVAILABLE", "The refused-message log is not available")
    return log


@router.get("/refusals", summary="Messages the assistant refused (ADMIN)")
async def refusals(
    log: Annotated[RefusalLog, Depends(refusal_log)],
    limit: Annotated[int, Query(ge=1, le=MAX_LIMIT)] = 100,
    topic: Annotated[Literal["OFF_TOPIC", "BUSINESS"] | None, Query()] = None,
    role: Annotated[Literal["GUEST", "CUSTOMER", "ADMIN"] | None, Query()] = None,
    since: Annotated[datetime | None, Query(description="Only messages at or after this time (ISO 8601).")] = None,
    format: Annotated[Literal["json", "csv"], Query()] = "json",
):
    rows = await log.recent(limit=limit, topic=topic, role=role, since=since)
    if format == "json":
        return {"items": [{**r, "time": r["time"].isoformat()} for r in rows]}
    out = io.StringIO()
    writer = csv.writer(out)
    writer.writerow(["time", "topic", "role", "text"])
    for r in rows:
        writer.writerow([r["time"].isoformat(), r["topic"], r["role"], _csv_safe(r["text"])])
    return Response(out.getvalue(), media_type="text/csv; charset=utf-8", headers={"Content-Disposition": 'attachment; filename="refused-messages.csv"', "Cache-Control": "no-store"})


def _csv_safe(value: str) -> str:
    """A spreadsheet runs a cell that starts with = + - @ as a formula; the text is user input, so it is defused."""
    return "'" + value if value[:1] in ("=", "+", "-", "@", "\t", "\r") else value
