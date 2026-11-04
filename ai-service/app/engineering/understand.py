"""Turns an engineer's question into STRUCTURED filters with rules only. The question never becomes SQL, a tool name or a prompt that can
select tools: it only picks from the fixed vocabulary below (windows, services, topics, sections), so a question (or a log line quoted in it)
cannot widen what is queried.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone

_UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
_LAST = re.compile(r"(?i)\b(?:in |over |during )?(?:the )?(?:last|past|previous)\s+(\d+)\s*(minute|min|hour|hr|day)s?\b")
_LAST_ONE = re.compile(r"(?i)\b(?:in |over |during )?(?:the )?(?:last|past)\s+(hour|day|week)\b")
_SINCE_DEPLOY = re.compile(r"(?i)\b(?:since|after|following)\s+(?:the\s+)?(?:latest|last|most recent|recent|new|current)?\s*(?:deploy\w*|release|rollout|version)\b")

SERVICE_WORDS = [("backend", re.compile(r"(?i)\b(backend|java|spring|commerce|api server)\b")),
                 ("ai-service", re.compile(r"(?i)\b(ai[- ]service|python|fastapi|assistant|chatbot|langgraph)\b")),
                 ("postgres", re.compile(r"(?i)\b(postgres(?:ql)?|database server)\b")),
                 ("tika", re.compile(r"(?i)\btika\b")),
                 ("frontend", re.compile(r"(?i)\b(frontend|browser|react|ui)\b"))]
TOPICS: list[tuple[str, re.Pattern[str], tuple[str, ...]]] = [
    ("checkout/payment", re.compile(r"(?i)\b(checkout|payment|pay|capture|order placement)\b"), ("checkout", "payment", "capture")),
    ("database", re.compile(r"(?i)\b(database|db|r2dbc|sql|connection pool)\b"), ("r2dbc", "postgres", "database", "connection pool", "sqlstate")),
    ("timeouts", re.compile(r"(?i)\btime[- ]?outs?\b|\btimed out\b"), ("timeout", "timed out")),
    ("authentication", re.compile(r"(?i)\b(auth\w*|login|jwt|token|401|403)\b"), ("auth", "jwt", "token", "login", "unauthor", "forbidden")),
    ("LLM", re.compile(r"(?i)\b(llm|gemini|vertex|model|quota)\b"), ("vertex", "gemini", "llm", "quota", "resource_exhausted")),
    ("orders", re.compile(r"(?i)\borders?\b"), ("order",)),
]
_5XX = re.compile(r"(?i)\b(5xx|http\s*5\d\d|server errors?|50[0-4])\b")


@dataclass
class EngQuery:
    intent: str                                    # INVESTIGATE | CLUSTERS | SEARCH | HEALTH | TRACE
    since: datetime
    until: datetime
    since_deployment: bool = False
    services: tuple[str, ...] = ()
    terms: tuple[str, ...] = ()
    min_status: int | None = None
    correlation_id: str | None = None
    notes: list[str] = field(default_factory=list)


def understand(question: str, as_of: datetime | None = None, default_hours: int = 24) -> EngQuery:
    q = question.strip()[:500]
    now = (as_of or datetime.now(timezone.utc)).astimezone(timezone.utc)
    notes: list[str] = []

    since, label = now - timedelta(hours=default_hours), f"default window of the last {default_hours} hours"
    since_dep = False
    if m := _LAST.search(q):
        n, unit = int(m.group(1)), m.group(2).lower()
        delta = timedelta(minutes=n) if unit.startswith("min") else timedelta(hours=n) if unit.startswith("h") else timedelta(days=n)
        since, label = now - min(delta, timedelta(days=31)), f"last {n} {unit}(s)"
    elif m := _LAST_ONE.search(q):
        since, label = now - {"hour": timedelta(hours=1), "day": timedelta(days=1), "week": timedelta(days=7)}[m.group(1).lower()], f"last {m.group(1).lower()}"
    elif re.search(r"(?i)\btoday\b", q):
        since, label = now.replace(hour=0, minute=0, second=0, microsecond=0), "today (UTC)"
    elif re.search(r"(?i)\byesterday\b", q):
        since, label = (now - timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0), "yesterday (UTC)"
    if _SINCE_DEPLOY.search(q):
        since_dep, label = True, "since the latest recorded deployment"
    notes.append(f"Time window: {label}")

    services = tuple(name for name, pat in SERVICE_WORDS if pat.search(q))
    if services:
        notes.append("Services: " + ", ".join(services))
    terms: list[str] = []
    topics = []
    for name, pat, words in TOPICS:
        if pat.search(q):
            topics.append(name)
            terms.extend(w for w in words if w not in terms)
    if topics:
        notes.append("Topics matched in log text: " + ", ".join(topics))
    min_status = 500 if _5XX.search(q) else None
    if min_status:
        notes.append("Only records with an HTTP status of 500 or more")

    cid = _UUID.search(q)
    if cid:
        intent = "TRACE"
        notes.append("Correlation id search")
    elif re.search(r"(?i)\b(health|status|overview|how (is|are))\b", q) and not re.search(r"(?i)\b(why|cause|fail)\b", q):
        intent = "HEALTH"
    elif re.search(r"(?i)\b(recurr\w*|repeat\w*|duplicate|group|cluster|frequent|top errors|common)\b", q) and not re.search(r"(?i)\bwhy\b", q):
        intent = "CLUSTERS"
    elif re.search(r"(?i)^\s*(show|list|find|search|get)\b", q) and not re.search(r"(?i)\b(why|cause)\b", q):
        intent = "SEARCH"
    else:
        intent = "INVESTIGATE"
    notes.append(f"Interpreted as: {intent.lower()}")
    return EngQuery(intent, since, now, since_dep, services, tuple(terms[:8]), min_status, cid.group(0) if cid else None, notes)
