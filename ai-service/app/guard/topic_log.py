"""The refused-message log: what people asked that the assistant would not answer, so staff can see which questions to cover or which attacks are being tried.

It stores four things: the topic the gate decided, the role of the asker (guest, customer or admin), the message text and the time. It never stores who asked: no
customer id or hash, no conversation id, no correlation id, so the log cannot be joined back to a person. The text is redacted (e-mail addresses, tokens, ids, long digit
runs such as phone and card numbers) and cut to MAX_TEXT characters before it is stored. Writing is best effort: a failed write is logged and never changes the reply.
Reading is ADMIN-only on the server (see topic_api); without a database the log keeps the latest MEMORY_LIMIT entries in memory.
"""
from __future__ import annotations

import logging
import re
from collections import deque
from datetime import datetime, timezone
from typing import Any

from app.obs.logging import redact_text

log = logging.getLogger(__name__)

MAX_TEXT = 300
MEMORY_LIMIT = 500
MAX_LIMIT = 1000
_DIGIT_RUN = re.compile(r"\d[\d\s().-]{4,}\d")
_SPACES = re.compile(r"\s+")


def clean(text: str) -> str:
    """What may be stored of a message: redacted, one line, bounded."""
    return _DIGIT_RUN.sub("[number]", redact_text(_SPACES.sub(" ", text or "").strip()))[:MAX_TEXT]


class RefusalLog:
    def __init__(self, pool: Any | None = None) -> None:
        self._pool = pool
        self._memory: deque[dict[str, Any]] = deque(maxlen=MEMORY_LIMIT)

    async def record(self, *, topic: str, role: str, text: str) -> None:
        entry = {"time": datetime.now(timezone.utc), "topic": topic, "role": role, "text": clean(text)}
        try:
            if self._pool is None:
                self._memory.appendleft(entry)
            else:
                await self._pool.execute("INSERT INTO topic_refusals (ts, topic, role, text) VALUES ($1, $2, $3, $4)", entry["time"], topic, role, entry["text"])
        except Exception:  # noqa: BLE001 - a logging failure must never change what the customer sees
            log.warning("could not record a refused message", exc_info=True)

    async def recent(self, *, limit: int = 100, topic: str | None = None, role: str | None = None, since: datetime | None = None) -> list[dict[str, Any]]:
        limit = max(1, min(limit, MAX_LIMIT))
        if self._pool is None:
            rows = [r for r in self._memory if (topic is None or r["topic"] == topic) and (role is None or r["role"] == role) and (since is None or r["time"] >= since)]
            return rows[:limit]
        rows = await self._pool.fetch(
            "SELECT ts, topic, role, text FROM topic_refusals WHERE ($1::text IS NULL OR topic = $1) AND ($2::text IS NULL OR role = $2) AND ($3::timestamptz IS NULL OR ts >= $3) "
            "ORDER BY ts DESC, id DESC LIMIT $4", topic, role, since, limit)
        return [{"time": r["ts"], "topic": r["topic"], "role": r["role"], "text": r["text"]} for r in rows]
