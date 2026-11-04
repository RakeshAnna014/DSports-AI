"""Normalised error signatures: the same defect must get the same signature whatever ids, numbers, timestamps or line numbers differ.

signature_of(message, exception) -> (signature, readable_text). Used to group recurring errors and to flag duplicate defects. Two records with the
same signature are the same failure observed twice; that is a fact. Whether they share a ROOT cause is a hypothesis and is labelled as one.
"""
from __future__ import annotations

import hashlib
import re

_UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")
_REF = re.compile(r"\b[A-Z]{2,6}-\d{4,}(?:-\d+)*\b")                       # ORD-20261007-000175, CASE-..., PAY-...
_HEX = re.compile(r"\b[0-9a-fA-F]{12,}\b")
_IP = re.compile(r"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b")
_TS = re.compile(r"\b\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:[.,]\d+)?(?:Z|[+-]\d\d:?\d\d)?\b")
_NUM = re.compile(r"\b\d+(?:\.\d+)?\b")
_QUOTED_ID = re.compile(r"'[^']{0,60}'|\"[^\"]{0,60}\"")
_WS = re.compile(r"\s+")
_JAVA_EXC = re.compile(r"^\s*(?:Caused by:\s*)?((?:[a-zA-Z_][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Failure|Timeout)[\w$]*)\s*(?::\s*(.*))?$")
_PY_EXC = re.compile(r"^([A-Z][\w]*(?:Error|Exception|Timeout|Failure)[\w]*)\s*(?::\s*(.*))?$")
_JAVA_FRAME = re.compile(r"^\s*at\s+((?:com\.dsports|org\.springframework)[\w.$<>]*)\(")
_PY_FRAME = re.compile(r'^\s*File ".*[\\/](app[\\/][^"]+)", line \d+, in (\w+)')


def normalise(text: str, limit: int = 160) -> str:
    t = _TS.sub("<ts>", text)
    t = _UUID.sub("<uuid>", t)
    t = _REF.sub("<ref>", t)
    t = _IP.sub("<ip>", t)
    t = _HEX.sub("<hex>", t)
    t = _QUOTED_ID.sub("<str>", t)
    t = _NUM.sub("<n>", t)
    return _WS.sub(" ", t).strip()[:limit]


def _exception_head(exception: str | None) -> tuple[str, str] | None:
    """The root exception class and message: the LAST 'Caused by' (Java) or the last exception line (Python)."""
    if not exception:
        return None
    found = None
    for line in exception.splitlines():
        m = _JAVA_EXC.match(line) or _PY_EXC.match(line.strip())
        if m:
            found = (m.group(1).rsplit(".", 1)[-1], normalise(m.group(2) or "", 100))
    return found


def _frames(exception: str | None, n: int = 3) -> list[str]:
    out: list[str] = []
    for line in (exception or "").splitlines():
        m = _JAVA_FRAME.match(line)
        if m:
            out.append(m.group(1))
        else:
            p = _PY_FRAME.match(line)
            if p:
                out.append(f"{p.group(1)}:{p.group(2)}")
        if len(out) >= n:
            break
    return out


def signature_of(message: str, exception: str | None = None, logger: str | None = None) -> tuple[str, str]:
    head = _exception_head(exception)
    if head:
        readable = f"{head[0]}: {head[1]}".rstrip(": ")
        key = "|".join([head[0], head[1], *_frames(exception)])
    else:
        readable = normalise(message)
        key = f"{(logger or '').rsplit('.', 1)[-1]}|{readable}"
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:12], readable
