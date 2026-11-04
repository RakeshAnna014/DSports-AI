"""Structured JSON logging with redaction. Secrets and personal data must never reach a log line."""
from __future__ import annotations

import json
import logging
import re
import sys
from datetime import datetime, timezone
from typing import Any

from app.obs.context import conversation_id_var, correlation_id_var, request_id_var

REDACTED = "[REDACTED]"

# Field names whose values are never logged, whatever they contain.
_SENSITIVE_KEYS = re.compile(
    r"(authorization|cookie|password|passwd|secret|token(?!s)|api[_-]?key|credential|private[_-]?key|"
    r"email|phone|mobile|address|line1|line2|postal|card|cvv|iban|account[_-]?number|customer[_-]?id|user[_-]?id)",
    re.IGNORECASE,
)
# Values that look sensitive wherever they appear in free text.
_VALUE_PATTERNS = [
    re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"),  # JWT
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"),
    re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"),  # e-mail
    re.compile(r"(?i)(postgres(?:ql)?|r2dbc:postgresql)://[^\s'\"]+"),  # connection strings (embed passwords)
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)", re.DOTALL),
    re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"),  # raw UUIDs (customer ids)
]
# Attributes that belong to logging.LogRecord itself, not to our extras.
_STANDARD = set(vars(logging.LogRecord("", 0, "", 0, "", (), None))) | {"message", "asctime", "taskName"}


def redact_text(value: str) -> str:
    for pattern in _VALUE_PATTERNS:
        value = pattern.sub(REDACTED, value)
    return value


def redact(value: Any, key: str | None = None) -> Any:
    if key is not None and _SENSITIVE_KEYS.search(key):
        return REDACTED
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {str(k): redact(v, str(k)) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [redact(v) for v in value]
    return value


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
            "correlationId": correlation_id_var.get(),
            "requestId": request_id_var.get(),
        }
        conversation_id = conversation_id_var.get()
        if conversation_id:
            entry["conversationId"] = conversation_id
        for key, value in record.__dict__.items():
            if key not in _STANDARD and not key.startswith("_"):
                entry[key] = redact(value, key)
        if record.exc_info:
            entry["exception"] = redact_text(self.formatException(record.exc_info))
        return json.dumps(entry, default=str, ensure_ascii=False)


class _StdoutHandler(logging.StreamHandler):
    """Writes to whatever sys.stdout is at emit time (so redirection/capture after configuration still works)."""

    def __init__(self) -> None:
        super().__init__(sys.stdout)

    @property
    def stream(self):  # type: ignore[override]
        return sys.stdout

    @stream.setter
    def stream(self, _value) -> None:
        pass


def configure_logging(level: str = "INFO") -> None:
    handler = _StdoutHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
    # Our middleware writes one structured line per request; uvicorn's access log would add the raw query string.
    logging.getLogger("uvicorn.access").disabled = True
    for noisy in ("httpx", "httpcore", "google_genai", "google.auth"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
