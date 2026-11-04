"""Deterministic log parsers for the formats this repository actually produces, plus redaction and signatures.

* backend   Spring Boot text:  `2026-10-13T16:05:34.456Z  INFO [<correlationId>] 21272 --- [app] [thread] logger : message` (+ stack-trace lines)
* ai-service JSON lines from app.obs.logging (`ts`, `level`, `logger`, `message`, `correlationId`, `requestId`, `status`, `exception`, ...)
* docker    `docker logs --timestamps` lines (infrastructure: postgres, tika ...)

Nothing here calls a model. A line that cannot be parsed is counted and skipped, never guessed.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Iterable, Iterator

from app.engineering.analysis import classify
from app.engineering.redact import redact_log_text
from app.engineering.signature import signature_of

LEVELS = {"TRACE": 0, "DEBUG": 1, "INFO": 2, "WARN": 3, "WARNING": 3, "ERROR": 4, "CRITICAL": 4, "FATAL": 4}
MAX_MESSAGE = 4000
MAX_EXCEPTION = 8000

_JAVA = re.compile(r"^(\d{4}-\d\d-\d\dT[\d:.]+Z)\s+(TRACE|DEBUG|INFO|WARN|ERROR)\s+\[([^\]]*)\]\s+(\d+)\s+---\s+\[([^\]]*)\]\s+\[([^\]]*)\]\s+(\S+)\s*:\s?(.*)$")
_DOCKER = re.compile(r"^(\d{4}-\d\d-\d\dT[\d:.]+Z)\s+(.*)$")
_PG_LEVEL = re.compile(r"\b(PANIC|FATAL|ERROR|WARNING|LOG|INFO|DEBUG\d?|NOTICE)\b:")
_STATUS_IN_TEXT = re.compile(r"(?i)\b(?:status|http)[\s=:]*([1-5]\d\d)\b")
_STACK_LINE = re.compile(r"^\s+(at |\.\.\. \d+ more)|^Caused by:|^\s*(?:File \"|Traceback )|^[\w.$]+(?:Exception|Error)\b")


@dataclass(frozen=True)
class ParsedLog:
    ts: datetime
    service: str
    level: str
    logger: str | None
    message: str
    exception: str | None
    correlation_id: str | None
    trace_id: str | None
    http_status: int | None
    signature: str | None
    signature_text: str | None
    raw_hash: str
    category: str | None = None


@dataclass
class IngestStats:
    lines: int = 0
    stored: int = 0
    skipped_below_level: int = 0
    unparsed: int = 0


def _level(raw: str) -> str:
    n = LEVELS.get(raw.upper(), 2)
    return {0: "DEBUG", 1: "DEBUG", 2: "INFO", 3: "WARN", 4: "ERROR"}[n]


def _ts(raw: str) -> datetime:
    return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(timezone.utc)


def _finish(service: str, source: str, line_no: int, ts: datetime, level: str, logger: str | None, message: str, exception: str | None,
            corr: str | None, trace: str | None, status: int | None) -> ParsedLog:
    message = (redact_log_text(message) or "")[:MAX_MESSAGE]
    exception = (redact_log_text(exception) or None)
    exception = exception[:MAX_EXCEPTION] if exception else None
    sig = text = category = None
    if level in ("WARN", "ERROR"):
        sig, text = signature_of(message, exception, logger)
        category = classify(f"{message}\n{exception or ''}")           # message AND stack trace: the wrapper names the dependency
    if status is None:
        m = _STATUS_IN_TEXT.search(message)
        status = int(m.group(1)) if m else None
    raw = f"{service}|{source}|{line_no}|{ts.isoformat()}|{level}|{message[:200]}"
    return ParsedLog(ts, service, level, logger, message, exception, corr or None, trace or None, status, sig, text, hashlib.sha1(raw.encode()).hexdigest(), category)


def parse_java(lines: Iterable[str], source: str, service: str = "backend") -> Iterator[tuple[ParsedLog | None, bool]]:
    """Yields (record, parsed_ok). Continuation lines (stack traces, multi-line SQL) are attached to the record before them."""
    pending: dict | None = None
    n = 0

    def flush() -> ParsedLog | None:
        if pending is None:
            return None
        return _finish(service, source, pending["n"], pending["ts"], pending["level"], pending["logger"], "\n".join(pending["msg"]),
                       "\n".join(pending["exc"]) or None, pending["corr"], None, None)

    for raw in lines:
        n += 1
        line = raw.rstrip("\r\n")
        m = _JAVA.match(line)
        if m:
            done = flush()
            if done:
                yield done, True
            ts, level, corr, _pid, _app, _thread, logger, message = m.groups()
            pending = {"n": n, "ts": _ts(ts), "level": _level(level), "logger": logger, "corr": corr.strip(), "msg": [message], "exc": []}
        elif pending is not None and line.strip():
            (pending["exc"] if _STACK_LINE.match(line) else pending["msg"]).append(line)
        elif pending is None and line.strip():
            yield None, False
    done = flush()
    if done:
        yield done, True


def parse_json_lines(lines: Iterable[str], source: str, service: str = "ai-service") -> Iterator[tuple[ParsedLog | None, bool]]:
    for n, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line:
            continue
        try:
            d = json.loads(line)
            ts = _ts(d["ts"])
        except (ValueError, KeyError, TypeError):
            yield None, False                    # e.g. uvicorn's own plain lines: counted, not guessed
            continue
        status = d.get("status")
        message = str(d.get("message", ""))
        if d.get("path") and not str(d.get("logger", "")).startswith("x"):
            message = f"{message} {d.get('method', '')} {d['path']} status={status} duration_ms={d.get('duration_ms')}".strip()
        yield _finish(service, source, n, ts, _level(str(d.get("level", "INFO"))), d.get("logger"), message, d.get("exception"),
                      d.get("correlationId"), d.get("traceId"), int(status) if isinstance(status, int) else None), True


def parse_docker(lines: Iterable[str], source: str, service: str) -> Iterator[tuple[ParsedLog | None, bool]]:
    for n, raw in enumerate(lines, 1):
        m = _DOCKER.match(raw.strip())
        if not m:
            if raw.strip():
                yield None, False
            continue
        text = m.group(2)
        pg = _PG_LEVEL.search(text)
        level = {"PANIC": "ERROR", "FATAL": "ERROR", "ERROR": "ERROR", "WARNING": "WARN"}.get(pg.group(1), "INFO") if pg else (
            "ERROR" if re.search(r"(?i)\b(error|exception|fatal)\b", text) else "INFO")
        yield _finish(service, source, n, _ts(m.group(1)), level, None, text, None, None, None, None), True


PARSERS = {"java": parse_java, "json": parse_json_lines, "docker": parse_docker}


def parse(kind: str, lines: Iterable[str], source: str, service: str, min_level: str = "INFO") -> tuple[list[ParsedLog], IngestStats]:
    stats = IngestStats()
    out: list[ParsedLog] = []
    floor = LEVELS[min_level]
    for rec, ok in PARSERS[kind](lines, source, service):
        if not ok or rec is None:
            stats.unparsed += 1
            continue
        stats.lines += 1
        if LEVELS[rec.level] < floor:
            stats.skipped_below_level += 1
            continue
        out.append(rec)
    return out, stats
