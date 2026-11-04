"""Log ingestion: files and containers -> parse -> redact -> store. Deterministic; re-ingesting the same lines adds nothing (position-based dedupe).

Connected sources in this repository today (see docs/ai/phase4): the backend's stdout log file, the AI service's JSON stdout log file, and docker
logs of the infrastructure containers. The frontend and any production platform are NOT connected (documented, with the configuration they need).
"""
from __future__ import annotations

import asyncio
import logging
import os
import subprocess
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.engineering.parsers import IngestStats, ParsedLog, parse
from app.engineering.repository import LogRepository
from app.engineering import synthetic

log = logging.getLogger(__name__)
FORMATS = {"backend": "java", "ai-service": "json", "postgres": "docker", "tika": "docker"}
MAX_LOG_FILE_BYTES = 64 * 1024 * 1024


def default_path(service: str) -> Path:
    env = {"backend": "ENG_BACKEND_LOG", "ai-service": "ENG_AI_LOG"}[service]
    name = {"backend": "dsports_backend.log", "ai-service": "ai_service_main.log"}[service]
    return Path(os.environ.get(env) or Path(tempfile.gettempdir()) / name)


def read_lines(path: Path, tail: int | None = None) -> list[str]:
    with path.open("r", encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    return lines[-tail:] if tail else lines


def docker_lines(container: str, since: str = "24h") -> list[str]:
    out = subprocess.run(["docker", "logs", "--timestamps", "--since", since, container], capture_output=True, text=True, timeout=60, check=False)
    return (out.stdout + out.stderr).splitlines()


async def ingest_lines(repo: LogRepository, kind: str, lines: list[str], service: str, origin: str, source: str, min_level: str = "INFO") -> dict[str, Any]:
    records, stats = parse(kind, lines, source, service, min_level)
    stored = await repo.insert(origin, source, records)
    return {"service": service, "origin": origin, "source": source, "lines": stats.lines, "unparsed": stats.unparsed, "below_level": stats.skipped_below_level, "stored_new": stored}


async def ingest_file(repo: LogRepository, path: Path, service: str, origin: str = "real", min_level: str = "INFO", tail: int | None = None) -> dict[str, Any]:
    return await ingest_lines(repo, FORMATS[service], read_lines(path, tail), service, origin, f"file:{path.name}", min_level)


async def ingest_docker(repo: LogRepository, container: str, service: str, since: str = "24h", min_level: str = "INFO") -> dict[str, Any]:
    return await ingest_lines(repo, "docker", docker_lines(container, since), service, "real", f"docker:{container}", min_level)


async def ingest_synthetic(repo: LogRepository, now=None, replace: bool = True) -> dict[str, Any]:
    """Loads the labelled synthetic incident (origin='synthetic') through the SAME parsers, and records its deployment."""
    inc = synthetic.generate(now)
    if replace:
        await repo.purge_origin_source("synthetic", f"{synthetic.SOURCE}:backend")
        await repo.purge_origin_source("synthetic", f"{synthetic.SOURCE}:ai-service")
        await repo._pool.execute("DELETE FROM eng_deployments WHERE origin = 'synthetic'")  # noqa: SLF001 - housekeeping of generated rows only
    java = [ln for block in inc.java_lines for ln in block.split("\n")]
    a = await ingest_lines(repo, "java", java, "backend", "synthetic", f"{synthetic.SOURCE}:backend", "INFO")
    b = await ingest_lines(repo, "json", inc.ai_lines, "ai-service", "synthetic", f"{synthetic.SOURCE}:ai-service", "INFO")
    await repo.add_deployment(inc.deployment["ts"], inc.deployment["service"], inc.deployment["version"], "synthetic", inc.deployment["note"])
    return {"backend": a, "ai-service": b, "deployment": {**inc.deployment, "ts": inc.deployment["ts"].isoformat()}, "truth": {k: v for k, v in inc.truth.items() if k not in ("failing_correlation_ids", "now", "start")}}


# ------------------------------------------------------------------------------------------------ near-live refresh
# The connected sources are local files and containers, so "live" means: re-read them on demand (the UI polls) and store what is new. Idempotent:
# a file is read whole, so every line keeps its position and the position-based dedupe adds only the new lines; a container is read from just
# after the newest record already stored. Nothing is invented: a source that cannot be read is reported as unavailable, with the reason.

_REFRESH_LOCK = asyncio.Lock()
_last: dict[str, Any] = {"at": 0.0, "result": None}
REFRESH_MIN_INTERVAL_S = 5.0


async def _refresh_file(repo: LogRepository, service: str) -> dict[str, Any]:
    path = default_path(service)
    base = {"service": service, "source": f"file:{path.name}", "kind": "file"}
    try:
        size = path.stat().st_size
    except OSError:
        return {**base, "status": "unavailable", "reason": "the log file does not exist (is the service writing to it?)"}
    if size > MAX_LOG_FILE_BYTES:
        return {**base, "status": "unavailable", "reason": f"the log file is larger than {MAX_LOG_FILE_BYTES // 1024 // 1024} MB; rotate it or ingest a tail with the CLI"}
    res = await ingest_file(repo, path, service, "real", "INFO")
    return {**base, "status": "ok", "lines": res["lines"], "unparsed": res["unparsed"], "stored_new": res["stored_new"]}


async def _refresh_docker(repo: LogRepository, container: str, service: str) -> dict[str, Any]:
    source = f"docker:{container}"
    base = {"service": service, "source": source, "kind": "container"}
    newest = await repo.latest_ts(source)
    since = newest.isoformat() if newest else "2h"
    try:
        lines = await asyncio.to_thread(docker_lines, container, since)
    except (OSError, subprocess.SubprocessError) as exc:
        return {**base, "status": "unavailable", "reason": f"docker is not available here ({type(exc).__name__})"}
    records, stats = parse("docker", lines, source, service, "INFO")
    if not records and lines and any("No such container" in ln for ln in lines[:3]):
        return {**base, "status": "unavailable", "reason": f"container {container} was not found"}
    fresh = [r for r in records if newest is None or r.ts > newest]
    stored = await repo.insert("real", source, fresh)
    return {**base, "status": "ok", "lines": stats.lines, "unparsed": stats.unparsed, "stored_new": stored}


async def refresh_real_sources(repo: LogRepository, *, force: bool = False) -> dict[str, Any]:
    """Re-reads every configured REAL source and stores what is new. At most once every few seconds (the answer is reused), so polling cannot hammer it."""
    async with _REFRESH_LOCK:
        now = time.monotonic()
        if not force and _last["result"] is not None and now - _last["at"] < REFRESH_MIN_INTERVAL_S:
            return {**_last["result"], "throttled": True}
        results = []
        for service in ("backend", "ai-service"):
            try:
                results.append(await _refresh_file(repo, service))
            except Exception as exc:  # noqa: BLE001 - one broken source must not hide the others
                log.warning("refresh of %s failed", service, exc_info=True)
                results.append({"service": service, "source": service, "kind": "file", "status": "unavailable", "reason": f"could not be read ({type(exc).__name__})"})
        for container, service in ((os.environ.get("ENG_POSTGRES_CONTAINER", "dsports-postgres"), "postgres"),):
            try:
                results.append(await _refresh_docker(repo, container, service))
            except Exception as exc:  # noqa: BLE001
                log.warning("refresh of %s failed", container, exc_info=True)
                results.append({"service": service, "source": f"docker:{container}", "kind": "container", "status": "unavailable", "reason": f"could not be read ({type(exc).__name__})"})
        result = {"refreshed_at": datetime.now(timezone.utc).isoformat(), "sources": results, "throttled": False,
                  "stored_new": sum(r.get("stored_new", 0) for r in results)}
        _last.update(at=time.monotonic(), result=result)
        return result