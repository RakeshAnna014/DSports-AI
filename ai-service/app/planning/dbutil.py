"""Small helpers shared by the planning repositories: record conversion and the data-source constants."""
from __future__ import annotations

import json
import math
import uuid
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Iterable

import asyncpg

LIVE, DEMO_SRC, IMPORTED = "DSPORTS_LIVE", "POC_SYNTHETIC", "IMPORTED"
SOURCES = (LIVE, DEMO_SRC, IMPORTED)
SCOPE_SOURCES = {DEMO_SRC: (DEMO_SRC, IMPORTED), LIVE: (LIVE,)}
JSON_COLS = {"detail", "scope", "params", "results", "inputs", "selection", "features", "estimate", "stores", "observations", "before", "after", "filters", "findings", "llm", "unavailable"}


def _j(v: Any) -> Any:
    if isinstance(v, str):
        try:
            return json.loads(v)
        except ValueError:
            return v
    return v


def rec(r: asyncpg.Record | None) -> dict[str, Any] | None:
    if r is None:
        return None
    out: dict[str, Any] = {}
    for k, v in dict(r).items():
        if k in JSON_COLS:
            out[k] = _j(v)
        elif isinstance(v, uuid.UUID):
            out[k] = str(v)
        elif isinstance(v, (datetime, date)):
            out[k] = v.isoformat()
        elif isinstance(v, Decimal):
            out[k] = float(v)
        else:
            out[k] = v
    return out


def recs(rows: Iterable[asyncpg.Record]) -> list[dict[str, Any]]:
    return [rec(r) for r in rows]  # type: ignore[misc]


def _finite(v: Any) -> Any:
    """JSON has no NaN or Infinity: a non-finite number (a metric over nothing, a rate with a zero denominator) is stored as null, which means "not available", never as a number."""
    if isinstance(v, float):
        return v if math.isfinite(v) else None
    if isinstance(v, dict):
        return {k: _finite(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_finite(x) for x in v]
    return v


def dumps(v: Any) -> str:
    return json.dumps(_finite(v), default=str)


def money(x: float) -> Decimal:
    return Decimal(f"{x:.2f}")


def num(x: float | None, places: int = 3) -> Decimal | None:
    return None if x is None else Decimal(f"{float(x):.{places}f}")
