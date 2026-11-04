"""Pure incident analysis over already-retrieved, already-redacted log data. No I/O, no clock, no model.

Vocabulary (kept strict because the brief demands it):
  FACT        something the log records directly show: a count, a first/last time, a service, a correlation chain, a time gap.
  HYPOTHESIS  an explanation consistent with the facts. Always worded as a possibility, always lists its evidence and what would confirm it.
Nothing in this module ever produces a "confirmed root cause": logs alone can only support a hypothesis.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Iterable

from app.engineering.safe import safe_text

CATEGORIES = ("DATABASE", "TIMEOUT", "AUTH", "LLM", "DOWNSTREAM", "VALIDATION", "OTHER")
_CATEGORY_RULES: list[tuple[str, re.Pattern[str]]] = [
    ("DATABASE", re.compile(r"(?i)r2dbc|jdbc|postgres|sqlstate|connection pool|pool (is )?exhaust|pool acquire|from the pool|deadlock|duplicate key|could not (acquire|obtain) (a )?connection|acquir\w* (a )?connection|too many connections|hikari")),
    ("LLM", re.compile(r"(?i)vertex|gemini|llm|resource_exhausted|quota|model (call|unavailable)|token budget|generativelanguage|google\.genai")),
    ("TIMEOUT", re.compile(r"(?i)time[- ]?out|timed out|deadline exceeded|read timeout|connect timeout|did not respond")),
    ("AUTH", re.compile(r"(?i)\b401\b|\b403\b|unauthori[sz]ed|forbidden|jwt|token (expired|invalid)|authentication|access denied")),
    ("DOWNSTREAM", re.compile(r"(?i)connection (refused|reset)|connectexception|unreachable|bad gateway|service unavailable|\b50[234]\b|upstream|httpx|dsports client|downstream")),
    ("VALIDATION", re.compile(r"(?i)validation|invalid (request|argument|parameter)|\b400\b|\b422\b|constraint violation")),
]


def classify(text: str) -> str:
    for name, pattern in _CATEGORY_RULES:
        if pattern.search(text):
            return name
    return "OTHER"


@dataclass
class Cluster:
    signature: str
    text: str
    count: int
    first_seen: datetime
    last_seen: datetime
    services: list[str]
    level: str
    sample_ids: list[int]
    category: str = "OTHER"
    prev_count: int | None = None
    change: str = "NEW_IN_WINDOW"          # NEW_IN_WINDOW | INCREASED | DECREASED | STABLE | NOT_COMPARED

    def as_dict(self) -> dict[str, Any]:
        return {"signature": self.signature, "text": self.text, "count": self.count, "first_seen": self.first_seen.isoformat(), "last_seen": self.last_seen.isoformat(),
                "services": self.services, "level": self.level, "category": self.category, "sample_ids": self.sample_ids,
                "previous_window_count": self.prev_count, "change": self.change}


def build_clusters(rows: Iterable[dict[str, Any]], previous: dict[str, int] | None = None) -> list[Cluster]:
    """`rows` are cluster rows from the repository (signature, text, count, first_seen, ...). `previous` maps signature -> count in the window before."""
    out: list[Cluster] = []
    for r in rows:
        c = Cluster(r["signature"], r["text"], int(r["count"]), r["first_seen"], r["last_seen"], list(r["services"]), r["level"], list(r["sample_ids"]),
                    r.get("category") or classify(r["text"]))
        if previous is not None:
            c.prev_count = previous.get(c.signature, 0)
            c.change = change_label(c.count, c.prev_count)
        else:
            c.change = "NOT_COMPARED"
        out.append(c)
    out.sort(key=lambda c: (-c.count, c.category == "OTHER", c.signature))     # on a tie the classified failure comes first
    return out


def change_label(now: int, before: int) -> str:
    if before == 0:
        return "NEW_IN_WINDOW"
    ratio = now / before
    if ratio >= 1.5 and now - before >= 3:
        return "INCREASED"
    if ratio <= 0.5 and before - now >= 3:
        return "DECREASED"
    return "STABLE"


def duplicate_candidates(clusters: list[Cluster]) -> list[dict[str, Any]]:
    """Distinct signatures that probably describe ONE defect: same category and the same words once the leading exception wrapper is removed.
    A candidate list for a human to confirm, never a merge."""
    groups: dict[tuple[str, str], list[Cluster]] = {}
    for c in clusters:
        core = re.sub(r"^[A-Za-z]+(?:Exception|Error):\s*", "", c.text).lower()
        core = re.sub(r"<[a-z]+>", "", core)
        key = (c.category, " ".join(core.split()[:6]))
        groups.setdefault(key, []).append(c)
    return [{"category": k[0], "signatures": [c.signature for c in v], "texts": [c.text for c in v], "note": "Possible duplicate defects (similar normalised text): a person should confirm."}
            for k, v in groups.items() if len(v) > 1 and k[1]]


def error_rate_trend(buckets: list[dict[str, Any]]) -> dict[str, Any]:
    """`buckets`: [{start, errors, total}] ascending. Reports the direction as a FACT about counts, with the first half vs second half."""
    if len(buckets) < 2:
        return {"direction": "INSUFFICIENT_DATA", "first_half_errors": None, "second_half_errors": None}
    mid = len(buckets) // 2
    a, b = sum(x["errors"] for x in buckets[:mid]), sum(x["errors"] for x in buckets[mid:])
    direction = "RISING" if b >= 1.5 * max(a, 1) and b - a >= 3 else "FALLING" if a >= 1.5 * max(b, 1) and a - b >= 3 else "STEADY"     # same 1.5x rule as the per-signature change label
    peak = max(buckets, key=lambda x: x["errors"])
    return {"direction": direction, "first_half_errors": a, "second_half_errors": b, "peak_bucket": {"start": peak["start"].isoformat() if hasattr(peak["start"], "isoformat") else peak["start"], "errors": peak["errors"]}}


def deployment_effect(deployment: dict[str, Any] | None, clusters: list[Cluster], window_start: datetime) -> dict[str, Any] | None:
    """Which signatures were FIRST SEEN after a deployment (given the window covers time before it). Timing only: correlation is not causation."""
    if deployment is None:
        return None
    ts = deployment["ts"]
    covers_before = window_start < ts
    after = [c for c in clusters if c.first_seen >= ts and c.change in ("NEW_IN_WINDOW", "NOT_COMPARED") and c.level == "ERROR"] if covers_before else []
    after.sort(key=lambda c: (-c.count, c.category == "OTHER", c.signature))
    return {"deployment": {"id": deployment["id"], "service": deployment["service"], "version": deployment["version"], "ts": ts.isoformat(), "origin": deployment.get("origin", "real")},
            "window_covers_time_before_deployment": covers_before,
            "new_error_signatures_after": [{"signature": c.signature, "text": c.text, "count": c.count, "first_seen": c.first_seen.isoformat(),
                                            "minutes_after_deployment": round((c.first_seen - ts) / timedelta(minutes=1), 1)} for c in after]}


def trace_chain(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """One correlation id across services: the ordered chain, the first error, the services it crossed and the dependency it points at."""
    rows = sorted(rows, key=lambda r: (r["ts"], r["id"]))
    errors = [r for r in rows if r["level"] == "ERROR"]
    first = errors[0] if errors else None
    return {"correlation_id": rows[0]["correlation_id"] if rows else None, "records": len(rows), "services": sorted({r["service"] for r in rows}),
            "first_error": {"id": first["id"], "service": first["service"], "ts": first["ts"].isoformat(), "message": safe_text(first["message"], 300)[0],
                            "category": classify(first["message"] + " " + (first.get("exception") or ""))} if first else None,
            "duration_ms": round((rows[-1]["ts"] - rows[0]["ts"]).total_seconds() * 1000) if len(rows) > 1 else 0,
            "ids": [r["id"] for r in rows]}


@dataclass
class Hypothesis:
    statement: str
    evidence_ids: list[int]
    confidence: str                      # LOW | MEDIUM  (never HIGH: logs alone cannot confirm a cause)
    would_confirm: str
    category: str = "OTHER"

    def as_dict(self) -> dict[str, Any]:
        return {"status": "HYPOTHESIS", "statement": self.statement, "evidence_ids": self.evidence_ids, "confidence": self.confidence,
                "to_confirm": self.would_confirm, "category": self.category}


_CONFIRM = {
    "DATABASE": "database-side logs for the same period (pool size, active connections, slow queries) and the deployment diff for repository code",
    "TIMEOUT": "the callee's own latency and logs for the same correlation ids, plus the configured timeout values",
    "AUTH": "whether tokens/secrets changed (rotation, shared JWT secret) and the identity provider logs",
    "LLM": "the Vertex AI quota/billing dashboard and the model-call latency metrics",
    "DOWNSTREAM": "whether the downstream service was up (health endpoints, its own logs) at those times",
    "VALIDATION": "the offending request payloads (redacted) and whether a client release changed",
    "OTHER": "the full stack trace in the repository at the deployed version",
}


def hypotheses_for(clusters: list[Cluster], deployment_view: dict[str, Any] | None, chains: list[dict[str, Any]]) -> list[Hypothesis]:
    out: list[Hypothesis] = []
    for c in [c for c in clusters if c.level == "ERROR" and c.count >= 3][:4]:          # one or two occurrences are not a pattern: no hypothesis
        ids = c.sample_ids[:5]
        kind = "an unclassified" if c.category == "OTHER" else f"a {c.category.lower()}"
        why = f"A recurring error ({c.count} occurrences, '{c.text}') points at {kind} problem"
        conf = "MEDIUM" if c.count >= 10 and c.category != "OTHER" else "LOW"
        out.append(Hypothesis(f"{why}; this is a hypothesis from log text only.", ids, conf, _CONFIRM[c.category], c.category))
    for c in [c for c in clusters if c.level == "WARN" and c.count >= 20][:2]:
        out.append(Hypothesis(f"The warning '{c.text}' recurred {c.count} times between {c.first_seen:%m-%d %H:%M} and {c.last_seen:%m-%d %H:%M} UTC, which may mean a scheduled job keeps retrying a "
                              "condition that never clears. This is a hypothesis from log text only.", c.sample_ids[:5], "LOW",
                              "the job's schedule and whether the same record ids repeat in the full logs"))
    if deployment_view and deployment_view["new_error_signatures_after"]:
        n = deployment_view["new_error_signatures_after"][0]
        d = deployment_view["deployment"]
        out.append(Hypothesis(f"The error '{n['text']}' was first seen {n['minutes_after_deployment']} minutes after {d['service']} {d['version']} was deployed, so the release may be related. "
                              "Timing alone does not show causation.", [], "LOW" if n["minutes_after_deployment"] > 60 else "MEDIUM",
                              "the change set of that release and whether the error stops after a rollback or fix"))
    cross = [c for c in chains if c["first_error"] and len(c["services"]) > 1]
    if cross:
        fe = cross[0]["first_error"]
        out.append(Hypothesis(f"In {len(cross)} correlated request(s) the first error appears in {fe['service']} ({fe['category'].lower()}) before errors in the other services, so the failure may originate there.",
                              [fe["id"]], "MEDIUM" if len(cross) >= 2 else "LOW", _CONFIRM.get(fe["category"], _CONFIRM["OTHER"]), fe["category"]))
    return out


# ------------------------------------------------------------------------------------------------ claim guard

_CITATION = re.compile(r"\[L(\d+)\]")
_ASSERTIVE = re.compile(r"(?i)\b(the )?root cause (is|was)\b|\bcaused by\b|\bconfirmed\b|\bdefinitely\b|\bproven\b|\bwas the reason\b")
_HEDGE = re.compile(r"(?i)\b(hypothes\w*|likely|possibl\w*|may|might|could|suggests?|consistent with|appears?|probabl\w*|not confirmed|unconfirmed)\b")


def check_claims(text: str, evidence_ids: set[int]) -> dict[str, Any]:
    """Audits a model-written explanation: every citation must be a record that was actually retrieved, and any assertive cause statement
    must be hedged or it is reported as an UNSUPPORTED root-cause claim. The caller replaces failing text with the deterministic summary."""
    cited = {int(m) for m in _CITATION.findall(text)}
    unknown = sorted(cited - evidence_ids)
    unsupported = []
    for sentence in re.split(r"(?<=[.!?])\s+|\n+", text):
        if _ASSERTIVE.search(sentence) and not _HEDGE.search(sentence):
            unsupported.append(sentence.strip()[:200])
    uncited = [s.strip()[:120] for s in re.split(r"(?<=[.!?])\s+|\n+", text) if re.search(r"\d", s) and not _CITATION.search(s) and len(s.split()) > 6] if cited or evidence_ids else []
    return {"ok": not unknown and not unsupported, "unknown_citations": unknown, "unsupported_claims": unsupported, "cited": sorted(cited), "uncited_numeric_sentences": uncited[:3]}
