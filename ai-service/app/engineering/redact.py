"""Redaction applied at INGEST, so stored (and therefore retrievable, and LLM-visible) log text never holds secrets or personal data.

Correlation and trace ids are UUID-shaped but are NOT personal data and are needed for correlation, so raw UUIDs are kept; a UUID that is the
value of a customer/user identifier is redacted by its key instead.
"""
from __future__ import annotations

import re

REDACTED = "[REDACTED]"

_PATTERNS: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)", re.DOTALL), REDACTED),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*"), REDACTED),                                   # JWT
    (re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"), "Bearer " + REDACTED),
    (re.compile(r"(?i)\b(postgres(?:ql)?|r2dbc:postgresql|jdbc:postgresql|mysql|redis)://[^\s'\"\]]+"), REDACTED),            # connection strings
    (re.compile(r"(?i)\b(authorization|password|passwd|pwd|secret|api[_-]?key|access[_-]?token|refresh[_-]?token|token|credential)s?\b(\s*[=:]\s*|\"\s*:\s*\")[^\s,;\"'}\]]+"),
     lambda m: f"{m.group(1)}{m.group(2)}{REDACTED}"),                                                                       # key=value secrets
    (re.compile(r"(?i)\b(customer|user|owner)[_-]?id(\s*[=:]\s*|\"\s*:\s*\")[0-9a-f-]{8,}"), lambda m: f"{m.group(1)}Id{m.group(2)}{REDACTED}"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[EMAIL]"),
    (re.compile(r"(?<!\d)(?:\+?91[-\s]?)?[6-9]\d{9}(?!\d)"), "[PHONE]"),
    (re.compile(r"(?<![\d-])(?:\d[ -]?){13,19}(?![\d-])"), "[CARD?]"),
    (re.compile(r"(?i)\b(line1|line2|street|postal[_-]?code|pincode)\s*[=:]\s*[^,;\]}]+"), lambda m: f"{m.group(1)}={REDACTED}"),
]


def redact_log_text(value: str | None) -> str | None:
    if value is None:
        return None
    for pattern, replacement in _PATTERNS:
        value = pattern.sub(replacement, value)
    return value
