"""Engineering AI REST API (internal). Every route needs a verified token with the ADMIN role; hiding a page in the frontend is a convenience,
this dependency is the enforcement. Denials and every investigation are audited (who, what was asked, how much evidence; never log content).
Routes only read the log store, except POST /deployments (records a deployment fact). Nothing here changes any commerce data.
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from pydantic import BaseModel, ConfigDict, Field

from app.auth.jwt import Principal, current_principal
from app.engineering import ingest
from app.engineering.analysis import build_clusters
from app.engineering.incidents import build_prompt, investigate_incident, rank_incidents, reference_of
from app.engineering.investigate import Investigator, _evidence_row
from app.engineering.repo_evidence import RepoEvidence, default_root
from app.engineering.safe import safe_text
from app.engineering.redact import redact_log_text
from app.engineering.repository import InvalidQuery, LogFilter, LogRepository
from app.errors import ApiException
from app.obs import metrics
from app.obs.context import correlation_id_var

log = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/engineering", tags=["engineering-ai"])


async def admin(request: Request, principal: Principal = Depends(current_principal)) -> Principal:
    if not principal.is_admin:
        repo = getattr(request.app.state.services, "engineering", None)
        if repo is not None:
            try:
                await repo.audit(principal.customer_hash, "DENIED", {"path": request.url.path[:120]}, correlation_id_var.get())
            except Exception:  # noqa: BLE001
                log.warning("could not audit a denied engineering request", exc_info=True)
        metrics.GUARD_BLOCKS.labels("engineering", "forbidden").inc()
        raise ApiException(403, "FORBIDDEN", "Engineering AI is restricted to authorised engineering users")
    return principal


def repo(request: Request, _: Principal = Depends(admin)) -> LogRepository:
    """Depends on `admin`, so the role check always runs BEFORE anything about the service's state is revealed."""
    r = getattr(request.app.state.services, "engineering", None)
    if r is None:
        raise ApiException(503, "ENGINEERING_UNAVAILABLE", "Engineering AI needs the AI database, which is not configured or reachable")
    return r


class InvestigateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question: str = Field(min_length=3, max_length=500)
    origin: str | None = Field(None, pattern="^(real|synthetic)$")
    use_llm: bool = Field(False, alias="useLlm")


class DeploymentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    service: str = Field(min_length=2, max_length=40)
    version: str = Field(min_length=1, max_length=60)
    ts: datetime | None = None
    note: str | None = Field(None, max_length=200)


def _window(hours: float, since: datetime | None, until: datetime | None) -> tuple[datetime, datetime]:
    end = until or datetime.now(timezone.utc)
    return since or end - timedelta(hours=hours), end


async def _guard(coro):
    try:
        return await coro
    except InvalidQuery as exc:
        raise ApiException(422, "INVALID_REQUEST", str(exc)) from None


LIVE_S, DELAYED_S = 120, 900


def _freshness(last_ts: datetime, origin: str, now: datetime) -> dict:
    lag = max(int((now - last_ts).total_seconds()), 0)
    status = "SYNTHETIC" if origin == "synthetic" else "LIVE" if lag <= LIVE_S else "DELAYED" if lag <= DELAYED_S else "STALE"
    return {"lag_seconds": lag, "freshness": status}


@router.get("/sources")
async def sources(r: LogRepository = Depends(repo)):
    """What is actually connected: per service, origin and source, with record counts, time range and how far behind the newest stored record is.
    Nothing is implied beyond this list. LIVE/DELAYED/STALE describe the stored data (newest record age), not the health of the service itself."""
    now = datetime.now(timezone.utc)
    rows = await r.coverage()
    return {"server_time": now.isoformat(), "live_threshold_seconds": LIVE_S, "delayed_threshold_seconds": DELAYED_S,
            "sources": [{**c, "first_ts": c["first_ts"].isoformat(), "last_ts": c["last_ts"].isoformat(), **_freshness(c["last_ts"], c["origin"], now)} for c in rows]}


@router.post("/refresh")
async def refresh(p: Principal = Depends(admin), r: LogRepository = Depends(repo), force: bool = False):
    """Near-live: re-reads the configured REAL sources (log files, the Postgres container) and stores what is new. Idempotent and throttled to once every few seconds;
    a source that cannot be read is reported as unavailable with the reason, never skipped silently."""
    result = await ingest.refresh_real_sources(r, force=force)
    if not result.get("throttled"):
        await r.audit(p.customer_hash, "REFRESH", {"stored_new": result["stored_new"], "sources": [{"source": s["source"], "status": s["status"]} for s in result["sources"]]}, correlation_id_var.get())
    return result

@router.post("/investigate")
async def investigate(body: InvestigateRequest, request: Request, p: Principal = Depends(admin), r: LogRepository = Depends(repo)):
    llm = request.app.state.services.llm if body.use_llm else None
    result = await _guard(Investigator(r, llm).run(body.question, origin=body.origin, use_llm=body.use_llm))
    metrics.GUARD_BLOCKS.labels("engineering", "investigated").inc()
    await r.audit(p.customer_hash, "INVESTIGATE", {"question": redact_log_text(body.question)[:200], "intent": result["intent"], "evidence": len(result["evidence"]),
                                                   "summary_source": result["summary"]["source"], "origin": body.origin}, correlation_id_var.get())
    return result


def _range(hours: float, since: datetime | None, until: datetime | None) -> tuple[datetime, datetime]:
    """15 minutes to 31 days, or an explicit custom range. Naive timestamps are rejected (no silent timezone guessing)."""
    for label, v in (("since", since), ("until", until)):
        if v is not None and v.tzinfo is None:
            raise ApiException(422, "INVALID_REQUEST", f"{label} must include a timezone (for example 2026-10-15T10:00:00Z)")
    start, end = _window(hours, since, until)
    if end <= start:
        raise ApiException(422, "INVALID_REQUEST", "The time range must end after it starts")
    if end - start > timedelta(days=31):
        raise ApiException(422, "INVALID_REQUEST", "The time range is limited to 31 days")
    return start, end


def _log_row(x: dict) -> dict:
    row = _evidence_row(x)
    exc = x.get("exception")
    row.update({"category": x.get("category"), "reference": reference_of(x["signature"]) if x.get("signature") else None, "signature": x.get("signature"),
                "exception_excerpt": (safe_text(exc, 1200)[0] if exc else None), "source": x.get("source")})
    return row


@router.get("/logs")
async def logs(r: LogRepository = Depends(repo), hours: float = Query(1, gt=0, le=744), since: datetime | None = None, until: datetime | None = None,
               service: Annotated[list[str] | None, Query()] = None, level: str = "WARN", q: Annotated[list[str] | None, Query(max_length=60)] = None,
               correlationId: str | None = Query(None, max_length=64), minStatus: int | None = Query(None, ge=100, le=599), endpoint: str | None = Query(None, max_length=100),
               signature: str | None = Query(None, max_length=64), origin: str | None = Query(None, pattern="^(real|synthetic)$"),
               limit: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0, le=10000)):
    """Historical and recent log search (newest first) with filters and pagination. The window is `hours` back from now, or an explicit since/until range."""
    start, end = _range(hours, since, until)
    f = LogFilter(start, end, tuple(service or ()), level.upper(), origin, tuple(q or ()), correlationId, signature, minStatus, limit, offset, endpoint)
    rows = await _guard(r.search(f))
    total = await _guard(r.count(f))
    return {"window": {"since": start.isoformat(), "until": end.isoformat()}, "count": len(rows), "total": total, "offset": offset, "limit": limit,
            "has_more": offset + len(rows) < total, "records": [_log_row(x) for x in rows]}

@router.get("/clusters")
async def clusters(r: LogRepository = Depends(repo), hours: int = Query(24, ge=1, le=744), service: Annotated[list[str] | None, Query()] = None,
                   origin: str | None = Query(None, pattern="^(real|synthetic)$")):
    since, until = _window(hours, None, None)
    span = until - since
    f = LogFilter(since, until, tuple(service or ()), "WARN", origin)
    prev = await _guard(r.signature_counts(LogFilter(since - span, since, tuple(service or ()), "WARN", origin)))
    return {"window": {"since": since.isoformat(), "until": until.isoformat()}, "clusters": [c.as_dict() for c in build_clusters(await _guard(r.clusters(f)), prev)]}


@router.get("/health")
async def service_health(r: LogRepository = Depends(repo), hours: int = Query(24, ge=1, le=744), origin: str | None = Query(None, pattern="^(real|synthetic)$")):
    since, until = _window(hours, None, None)
    rows = await r.service_health(since, until, origin)
    return {"window": {"since": since.isoformat(), "until": until.isoformat()}, "services": [{**x, "last_seen": x["last_seen"].isoformat() if x["last_seen"] else None} for x in rows],
            "note": "Derived from ingested log records, not from alerts. AI-written text elsewhere is labelled and cites record ids."}


@router.get("/trace/{correlation_id}")
async def trace(correlation_id: str, r: LogRepository = Depends(repo)):
    from app.engineering.analysis import trace_chain
    rows = await r.trace(correlation_id[:64])
    return {"chain": trace_chain(rows) if rows else None, "records": [_evidence_row(x) for x in rows]}


@router.get("/deployments")
async def deployments(r: LogRepository = Depends(repo), days: int = Query(7, ge=1, le=90)):
    now = datetime.now(timezone.utc)
    rows = await r.deployments(now - timedelta(days=days), now + timedelta(hours=1))
    return {"deployments": [{**d, "ts": d["ts"].isoformat()} for d in rows]}


@router.post("/deployments", status_code=201)
async def record_deployment(body: DeploymentRequest, p: Principal = Depends(admin), r: LogRepository = Depends(repo)):
    did = await r.add_deployment(body.ts or datetime.now(timezone.utc), body.service, body.version, "real", body.note)
    await r.audit(p.customer_hash, "DEPLOYMENT_RECORDED", {"service": body.service, "version": body.version}, correlation_id_var.get())
    return {"id": did}


# ------------------------------------------------------------------------------------------------ incidents (derived groups of log records)

class IncidentRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    signature: str = Field(min_length=6, max_length=64, pattern=r"^[A-Za-z0-9_\-]+$")
    since: datetime
    until: datetime
    origin: str | None = Field(None, pattern="^(real|synthetic)$")


def _incident_window(body: IncidentRequest) -> tuple[datetime, datetime]:
    return _range(1, body.since, body.until)


@router.get("/incidents")
async def incidents(r: LogRepository = Depends(repo), hours: float = Query(24, gt=0, le=744), since: datetime | None = None, until: datetime | None = None,
                    service: Annotated[list[str] | None, Query()] = None, origin: str | None = Query(None, pattern="^(real|synthetic)$"),
                    limit: int = Query(10, ge=1, le=50), offset: int = Query(0, ge=0, le=200)):
    """The actionable incident groups in a window, ranked (severity, recency, trend, capped volume and impact: never raw volume alone). Default 10 per page; use offset to see more."""
    start, end = _range(hours, since, until)
    result = await _guard(rank_incidents(r, since=start, until=end, origin=origin, services=tuple(service or ()), limit=limit, offset=offset))
    now = datetime.now(timezone.utc)
    result["data_freshness"] = [{"service": c["service"], "origin": c["origin"], "source": c["source"], "last_ts": c["last_ts"].isoformat(), **_freshness(c["last_ts"], c["origin"], now)} for c in await r.coverage()]
    return result


@router.post("/incidents/investigate")
async def incident_investigation(body: IncidentRequest, p: Principal = Depends(admin), r: LogRepository = Depends(repo)):
    """Root-cause investigation of one incident group: logs before/after, cross-service requests, dependency errors, git and source evidence. Ranked HYPOTHESES only."""
    start, end = _incident_window(body)
    report = await _guard(investigate_incident(r, RepoEvidence(default_root()), signature=body.signature, since=start, until=end, origin=body.origin))
    if report is None:
        raise ApiException(404, "NOT_FOUND", "No log records with that signature in the selected window")
    origin_key = body.origin or (report["origin"] if report["origin"] in ("real", "synthetic") else "real")
    await r.set_incident_status(body.signature, origin_key, p.customer_hash)
    await r.audit(p.customer_hash, "INVESTIGATE_INCIDENT", {"reference": report["reference"], "evidence": len(report["observed_evidence"]), "files": len(report["code"]["files"]),
                                                         "git_available": report["git"]["available"]}, correlation_id_var.get())
    metrics.GUARD_BLOCKS.labels("engineering", "incident_investigated").inc()
    return report


@router.post("/incidents/prompt")
async def incident_prompt(body: IncidentRequest, p: Principal = Depends(admin), r: LogRepository = Depends(repo)):
    """A repository-aware coding prompt for the incident, built from the REDACTED investigation (never from raw logs, secrets or unrestricted text).
    Generating a prompt does not fix anything; it only prepares the request for a coding assistant."""
    start, end = _incident_window(body)
    report = await _guard(investigate_incident(r, RepoEvidence(default_root()), signature=body.signature, since=start, until=end, origin=body.origin))
    if report is None:
        raise ApiException(404, "NOT_FOUND", "No log records with that signature in the selected window")
    prompt = build_prompt(report)
    warnings = ["Generating this prompt does not fix the incident: a person (or a coding assistant under review) still has to change, test and deploy the code."]
    if report["origin"] != "real":
        warnings.append("The evidence includes SYNTHETIC test data: this may not be a real incident.")
    await r.audit(p.customer_hash, "INCIDENT_PROMPT", {"reference": report["reference"], "chars": len(prompt)}, correlation_id_var.get())
    return {"reference": report["reference"], "prompt": prompt, "characters": len(prompt), "warnings": warnings, "origin": report["origin"]}