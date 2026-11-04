"""Log store access. Every query is parameterised and bounded (row limits, time windows); there is no way to pass raw SQL, a table name or an
unbounded query through this interface, which is what keeps the tool surface safe to expose to an LLM-driven flow.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

import asyncpg

from app.engineering.parsers import LEVELS, ParsedLog

MAX_ROWS = 200
SERVICES = ("backend", "ai-service", "postgres", "tika", "frontend")
_COLUMNS = "id, ts, service, origin, source, level, logger, message, exception, correlation_id, trace_id, http_status, signature"


class InvalidQuery(ValueError):
    """A request outside the allowed shape (unknown service, absurd window ...): a 422, never silently widened."""


@dataclass(frozen=True)
class LogFilter:
    since: datetime
    until: datetime
    services: tuple[str, ...] = ()
    min_level: str = "INFO"
    origin: str | None = None                 # real | synthetic | None (both, always labelled in results)
    terms: tuple[str, ...] = ()               # ANY of these (case-insensitive) in message/logger/exception
    correlation_id: str | None = None
    signature: str | None = None
    min_status: int | None = None
    limit: int = 50
    offset: int = 0                           # pagination of search results (newest first)
    endpoint: str | None = None               # a request path fragment such as /api/v1/orders (matched in the message text, parameter-bound)

    def validate(self) -> "LogFilter":
        if self.until <= self.since:
            raise InvalidQuery("The time window must end after it starts")
        if self.until - self.since > timedelta(days=31):
            raise InvalidQuery("The time window is limited to 31 days")
        if self.min_level not in ("DEBUG", "INFO", "WARN", "ERROR"):
            raise InvalidQuery("Unknown level")
        bad = [s for s in self.services if s not in SERVICES]
        if bad:
            raise InvalidQuery(f"Unknown service {bad[0]!r}. Known: {', '.join(SERVICES)}")
        if self.origin not in (None, "real", "synthetic"):
            raise InvalidQuery("origin must be real or synthetic")
        if len(self.terms) > 8 or any(len(t) > 60 for t in self.terms):
            raise InvalidQuery("At most 8 search terms of 60 characters")
        if not 0 <= self.offset <= 10_000:
            raise InvalidQuery("offset must be between 0 and 10000")
        if self.endpoint is not None and not re.fullmatch(r"/[A-Za-z0-9_\-./{}:]{0,99}", self.endpoint):
            raise InvalidQuery("endpoint must be a request path such as /api/v1/orders")
        return self


def _where(f: LogFilter, start: int = 1) -> tuple[str, list[Any]]:
    params: list[Any] = [f.since, f.until]
    sql = ["ts >= $1", "ts < $2"]
    floor = LEVELS[f.min_level]
    levels = [lv for lv in ("DEBUG", "INFO", "WARN", "ERROR") if LEVELS[lv] >= floor]
    params.append(levels)
    sql.append(f"level = ANY(${len(params)})")
    if f.services:
        params.append(list(f.services)); sql.append(f"service = ANY(${len(params)})")
    if f.origin:
        params.append(f.origin); sql.append(f"origin = ${len(params)}")
    if f.correlation_id:
        params.append(f.correlation_id); sql.append(f"correlation_id = ${len(params)}")
    if f.signature:
        params.append(f.signature); sql.append(f"signature = ${len(params)}")
    if f.min_status:
        params.append(f.min_status); sql.append(f"http_status >= ${len(params)}")
    if f.endpoint:
        params.append(f"%{f.endpoint.lower()}%"); sql.append(f"lower(message) LIKE ${len(params)}")
    if f.terms:
        likes = []
        for t in f.terms:
            params.append(f"%{t.lower().replace('%', '').replace('_', ' ')}%")
            likes.append(f"(lower(message) LIKE ${len(params)} OR lower(coalesce(logger,'')) LIKE ${len(params)} OR lower(coalesce(exception,'')) LIKE ${len(params)})")
        sql.append("(" + " OR ".join(likes) + ")")
    return " AND ".join(sql), params


class LogRepository:
    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool

    # ------------------------------------------------------------------ ingestion
    async def insert(self, service_origin: str, source: str, records: list[ParsedLog]) -> int:
        if not records:
            return 0
        rows = [(r.ts, r.service, service_origin, source, r.level, r.logger, r.message, r.exception, r.correlation_id, r.trace_id, r.http_status,
                 r.signature, r.signature_text, r.raw_hash, r.category) for r in records]
        async with self._pool.acquire() as conn:
            before = await conn.fetchval("SELECT count(*) FROM eng_logs")
            await conn.executemany(
                "INSERT INTO eng_logs (ts, service, origin, source, level, logger, message, exception, correlation_id, trace_id, http_status, signature, signature_text, raw_hash, category) "
                "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15) ON CONFLICT (raw_hash) DO NOTHING", rows)
            return int(await conn.fetchval("SELECT count(*) FROM eng_logs") - before)

    async def purge_origin_source(self, origin: str, source: str) -> int:
        res = await self._pool.execute("DELETE FROM eng_logs WHERE origin = $1 AND source = $2", origin, source)
        return int(res.split()[-1])

    async def add_deployment(self, ts: datetime, service: str, version: str, origin: str = "real", note: str | None = None) -> int:
        return int(await self._pool.fetchval("INSERT INTO eng_deployments (ts, service, version, origin, note) VALUES ($1,$2,$3,$4,$5) RETURNING id", ts, service, version, origin, note))

    # ------------------------------------------------------------------ queries
    async def search(self, f: LogFilter) -> list[dict[str, Any]]:
        f.validate()
        where, params = _where(f)
        params.append(min(max(f.limit, 1), MAX_ROWS))
        params.append(f.offset)
        rows = await self._pool.fetch(f"SELECT {_COLUMNS} FROM eng_logs WHERE {where} ORDER BY ts DESC, id DESC LIMIT ${len(params) - 1} OFFSET ${len(params)}", *params)
        return [dict(r) for r in rows]

    async def count(self, f: LogFilter) -> int:
        f.validate()
        where, params = _where(f)
        return int(await self._pool.fetchval(f"SELECT count(*) FROM eng_logs WHERE {where}", *params))

    async def clusters(self, f: LogFilter, limit: int = 15) -> list[dict[str, Any]]:
        f.validate()
        where, params = _where(LogFilter(f.since, f.until, f.services, "WARN" if f.min_level in ("DEBUG", "INFO") else f.min_level, f.origin, f.terms, f.correlation_id,
                                         f.signature, f.min_status))
        params.append(min(max(limit, 1), 50))
        rows = await self._pool.fetch(
            f"SELECT signature, (array_agg(signature_text ORDER BY ts DESC))[1] AS text, mode() WITHIN GROUP (ORDER BY category) AS category, count(*) AS count, min(ts) AS first_seen, max(ts) AS last_seen, "
            f"array_agg(DISTINCT service) AS services, (CASE WHEN bool_or(level = 'ERROR') THEN 'ERROR' ELSE 'WARN' END) AS level, "
            f"(array_agg(id ORDER BY ts DESC))[1:5] AS sample_ids FROM eng_logs WHERE {where} AND signature IS NOT NULL "
            f"GROUP BY signature ORDER BY count(*) DESC, signature LIMIT ${len(params)}", *params)
        return [dict(r) for r in rows]

    async def signature_counts(self, f: LogFilter) -> dict[str, int]:
        f.validate()
        where, params = _where(LogFilter(f.since, f.until, f.services, "WARN", f.origin, f.terms, None, None, f.min_status))
        rows = await self._pool.fetch(f"SELECT signature, count(*) AS n FROM eng_logs WHERE {where} AND signature IS NOT NULL GROUP BY signature", *params)
        return {r["signature"]: int(r["n"]) for r in rows}

    async def first_seen(self, signatures: list[str], origin: str | None) -> dict[str, datetime]:
        rows = await self._pool.fetch("SELECT signature, min(ts) AS first FROM eng_logs WHERE signature = ANY($1) AND ($2::text IS NULL OR origin = $2) GROUP BY signature",
                                      signatures, origin)
        return {r["signature"]: r["first"] for r in rows}

    async def error_span(self, f: LogFilter) -> tuple[datetime, datetime] | None:
        """First and last ERROR matching the filter, so a trend can be measured over the period that actually has errors."""
        f.validate()
        where, params = _where(LogFilter(f.since, f.until, f.services, "ERROR", f.origin, f.terms, None, None, f.min_status))
        r = await self._pool.fetchrow(f"SELECT min(ts) AS first, max(ts) AS last FROM eng_logs WHERE {where} AND level = 'ERROR'", *params)
        return (r["first"], r["last"]) if r and r["first"] else None

    async def buckets(self, f: LogFilter, minutes: int) -> list[dict[str, Any]]:
        f.validate()
        minutes = min(max(minutes, 1), 1440)
        where, params = _where(LogFilter(f.since, f.until, f.services, "INFO", f.origin, f.terms, None, None, f.min_status))
        params.append(minutes * 60)
        rows = await self._pool.fetch(
            f"SELECT to_timestamp(floor(extract(epoch FROM ts) / ${len(params)}) * ${len(params)}) AS start, count(*) FILTER (WHERE level = 'ERROR') AS errors, count(*) AS total "
            f"FROM eng_logs WHERE {where} GROUP BY 1 ORDER BY 1", *params)
        return [{"start": r["start"], "errors": int(r["errors"]), "total": int(r["total"])} for r in rows]

    async def service_health(self, since: datetime, until: datetime, origin: str | None) -> list[dict[str, Any]]:
        rows = await self._pool.fetch(
            "SELECT service, count(*) AS total, count(*) FILTER (WHERE level = 'ERROR') AS errors, count(*) FILTER (WHERE level = 'WARN') AS warnings, "
            "count(*) FILTER (WHERE http_status >= 500) AS http_5xx, max(ts) AS last_seen FROM eng_logs WHERE ts >= $1 AND ts < $2 AND ($3::text IS NULL OR origin = $3) "
            "GROUP BY service ORDER BY errors DESC, service", since, until, origin)
        return [dict(r) for r in rows]

    async def trace(self, correlation_id: str, limit: int = 100) -> list[dict[str, Any]]:
        rows = await self._pool.fetch(f"SELECT {_COLUMNS} FROM eng_logs WHERE correlation_id = $1 ORDER BY ts, id LIMIT $2", correlation_id, min(limit, MAX_ROWS))
        return [dict(r) for r in rows]

    async def error_correlation_ids(self, f: LogFilter, limit: int = 5) -> list[str]:
        f.validate()
        where, params = _where(LogFilter(f.since, f.until, f.services, "ERROR", f.origin, f.terms, None, f.signature, f.min_status))
        params.append(limit)
        rows = await self._pool.fetch(f"SELECT correlation_id FROM eng_logs WHERE {where} AND correlation_id IS NOT NULL AND correlation_id <> '' "
                                      f"GROUP BY correlation_id ORDER BY max(ts) DESC LIMIT ${len(params)}", *params)
        return [r["correlation_id"] for r in rows]

    async def latest_ts(self, source: str, origin: str = 'real') -> datetime | None:
        return await self._pool.fetchval('SELECT max(ts) FROM eng_logs WHERE source = $1 AND origin = $2', source, origin)

    async def signature_impact(self, f: LogFilter, signatures: list[str]) -> dict[str, dict[str, Any]]:
        """Per signature: distinct correlation ids (a proxy for affected requests) and the services involved. A proxy, not a customer count."""
        f.validate()
        if not signatures:
            return {}
        where, params = _where(LogFilter(f.since, f.until, f.services, "WARN", f.origin))
        params.append(signatures)
        rows = await self._pool.fetch(
            f"SELECT signature, count(DISTINCT correlation_id) FILTER (WHERE correlation_id IS NOT NULL AND correlation_id <> '') AS requests, "
            f"count(*) FILTER (WHERE http_status >= 500) AS http_5xx, array_agg(DISTINCT service) AS services FROM eng_logs WHERE {where} AND signature = ANY(${len(params)}) GROUP BY signature", *params)
        return {r['signature']: {'requests': int(r['requests']), 'http_5xx': int(r['http_5xx']), 'services': list(r['services'])} for r in rows}

    async def signature_records(self, signature: str, f: LogFilter, limit: int = 8, newest: bool = True) -> list[dict[str, Any]]:
        f.validate()
        where, params = _where(LogFilter(f.since, f.until, f.services, "WARN", f.origin, signature=signature))
        params.append(min(max(limit, 1), 40))
        order = 'DESC' if newest else 'ASC'
        rows = await self._pool.fetch(f"SELECT {_COLUMNS} FROM eng_logs WHERE {where} ORDER BY ts {order}, id {order} LIMIT ${len(params)}", *params)
        return [dict(r) for r in rows]

    async def incident_status(self, signatures: list[str], origin: str | None) -> dict[str, dict[str, Any]]:
        if not signatures:
            return {}
        rows = await self._pool.fetch("SELECT signature, origin, status, investigated_at FROM eng_incident_status WHERE signature = ANY($1) AND ($2::text IS NULL OR origin = $2)", signatures, origin)
        return {r['signature']: {'status': r['status'], 'investigated_at': r['investigated_at']} for r in rows}

    async def set_incident_status(self, signature: str, origin: str, actor_hash: str, status: str = 'INVESTIGATED', note: str | None = None) -> None:
        await self._pool.execute(
            "INSERT INTO eng_incident_status (signature, origin, status, actor_hash, note) VALUES ($1,$2,$3,$4,$5) "
            "ON CONFLICT (signature, origin) DO UPDATE SET status = EXCLUDED.status, investigated_at = now(), actor_hash = EXCLUDED.actor_hash, note = EXCLUDED.note",
            signature, origin, status, actor_hash, note)

    async def records(self, ids: list[int]) -> list[dict[str, Any]]:
        rows = await self._pool.fetch(f"SELECT {_COLUMNS} FROM eng_logs WHERE id = ANY($1) ORDER BY ts, id", ids[:MAX_ROWS])
        return [dict(r) for r in rows]

    async def deployments(self, since: datetime, until: datetime, origin: str | None = None) -> list[dict[str, Any]]:
        rows = await self._pool.fetch("SELECT id, ts, service, version, origin, note FROM eng_deployments WHERE ts >= $1 AND ts < $2 AND ($3::text IS NULL OR origin = $3) ORDER BY ts DESC",
                                      since, until, origin)
        return [dict(r) for r in rows]

    async def latest_deployment(self, until: datetime, origin: str | None = None) -> dict[str, Any] | None:
        r = await self._pool.fetchrow("SELECT id, ts, service, version, origin, note FROM eng_deployments WHERE ts <= $1 AND ($2::text IS NULL OR origin = $2) ORDER BY ts DESC LIMIT 1", until, origin)
        return dict(r) if r else None

    async def coverage(self) -> list[dict[str, Any]]:
        rows = await self._pool.fetch("SELECT service, origin, source, count(*) AS records, min(ts) AS first_ts, max(ts) AS last_ts FROM eng_logs GROUP BY service, origin, source ORDER BY service, origin, source")
        return [dict(r) for r in rows]

    async def audit(self, actor_hash: str, action: str, detail: dict[str, Any], correlation_id: str | None) -> None:
        await self._pool.execute("INSERT INTO eng_audit (actor_hash, action, detail, correlation_id) VALUES ($1,$2,$3::jsonb,$4)", actor_hash, action, json.dumps(detail, default=str), correlation_id)
