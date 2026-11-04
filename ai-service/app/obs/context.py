"""Per-request identifiers carried in contextvars so every log line can include them."""
from __future__ import annotations

import re
import uuid
from contextvars import ContextVar

correlation_id_var: ContextVar[str | None] = ContextVar("correlation_id", default=None)
request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)
conversation_id_var: ContextVar[str | None] = ContextVar("conversation_id", default=None)

# Same spirit as the Java backend: accept only a short, safe token, otherwise generate our own.
_SAFE_CORRELATION_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


def resolve_correlation_id(supplied: str | None) -> str:
    if supplied and _SAFE_CORRELATION_ID.fullmatch(supplied):
        return supplied
    return str(uuid.uuid4())
