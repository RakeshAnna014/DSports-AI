"""Engineering AI tools in the SAME typed registry (and MCP facade) as the other tools. All READ, all restricted to ADMIN by the registry itself.
There is no free-form query, SQL, shell or file argument anywhere: every argument is a bounded, typed filter, and results are redacted records.
A log line can therefore never make a tool run: tools are chosen by the caller, not by log text.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Literal

from pydantic import Field

from app.engineering.analysis import build_clusters
from app.engineering.investigate import Investigator, _evidence_row
from app.engineering.repository import InvalidQuery, LogFilter
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

ADMIN = "ADMIN"
Origin = Literal["real", "synthetic"]
Level = Literal["INFO", "WARN", "ERROR"]


class WindowArgs(StrictArgs):
    hours: int = Field(24, ge=1, le=744)
    service: Literal["backend", "ai-service", "postgres", "tika", "frontend"] | None = None
    origin: Origin | None = None


class SearchArgs(WindowArgs):
    hours: int = Field(1, ge=1, le=744)
    level: Level = "WARN"
    terms: list[str] | None = Field(None, max_length=8)
    correlation_id: str | None = Field(None, max_length=64)
    min_status: int | None = Field(None, ge=100, le=599)
    limit: int = Field(30, ge=1, le=100)


class TraceArgs(StrictArgs):
    correlation_id: str = Field(min_length=8, max_length=64)


class InvestigateArgs(StrictArgs):
    question: str = Field(min_length=3, max_length=500)
    origin: Origin | None = None


def _repo(ctx: ToolContext):
    r = getattr(ctx.services, "engineering", None)
    if r is None:
        raise ToolError("ENGINEERING_UNAVAILABLE", "Engineering AI needs the AI database")
    return r


def _f(a: WindowArgs, level: str = "WARN", **kw) -> LogFilter:
    until = datetime.now(timezone.utc)
    return LogFilter(until - timedelta(hours=a.hours), until, (a.service,) if a.service else (), level, a.origin, **kw)


async def search_logs(ctx: ToolContext, a: SearchArgs):
    try:
        rows = await _repo(ctx).search(_f(a, a.level, terms=tuple(a.terms or ()), correlation_id=a.correlation_id, min_status=a.min_status, limit=a.limit))
    except InvalidQuery as exc:
        raise ToolError("INVALID_REQUEST", str(exc)) from None
    return {"count": len(rows), "records": [_evidence_row(r) for r in rows]}


async def error_clusters(ctx: ToolContext, a: WindowArgs):
    repo = _repo(ctx)
    f = _f(a)
    span = f.until - f.since
    prev = await repo.signature_counts(LogFilter(f.since - span, f.since, f.services, "WARN", f.origin))
    return {"clusters": [c.as_dict() for c in build_clusters(await repo.clusters(f), prev)]}


async def service_health(ctx: ToolContext, a: WindowArgs):
    f = _f(a)
    rows = await _repo(ctx).service_health(f.since, f.until, a.origin)
    return {"services": [{**r, "last_seen": r["last_seen"].isoformat() if r["last_seen"] else None} for r in rows]}


async def trace_correlation(ctx: ToolContext, a: TraceArgs):
    from app.engineering.analysis import trace_chain
    rows = await _repo(ctx).trace(a.correlation_id)
    return {"chain": trace_chain(rows) if rows else None, "records": [_evidence_row(r) for r in rows]}


async def investigate_incident(ctx: ToolContext, a: InvestigateArgs):
    return await Investigator(_repo(ctx)).run(a.question, origin=a.origin)


def tools() -> list[Tool]:
    spec = [
        ("eng_search_logs", "Search redacted application logs by time window, service, level, text terms, correlation id or HTTP status.", SearchArgs, search_logs),
        ("eng_error_clusters", "Group recurring errors by normalised signature with counts, first/last seen and change versus the previous window.", WindowArgs, error_clusters),
        ("eng_service_health", "Per-service error/warning/5xx counts derived from ingested logs (not alerts).", WindowArgs, service_health),
        ("eng_trace", "All records sharing one correlation id, with the first error and the services it crossed.", TraceArgs, trace_correlation),
        ("eng_investigate", "Evidence-based incident investigation: facts, labelled hypotheses, clusters, deployment correlation and next steps.", InvestigateArgs, investigate_incident),
    ]
    return [Tool(name, desc, Kind.READ, args, handler, requires_auth=True, required_role=ADMIN) for name, desc, args, handler in spec]
