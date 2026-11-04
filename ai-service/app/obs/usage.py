"""Usage and cost tracking for model calls.

Cost is an ESTIMATE computed from an operator-supplied price table (AI_PRICE_TABLE_JSON). No prices are built in: a
model without an entry is recorded with cost_estimate = None rather than a guessed number.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Protocol

from app.obs import metrics
from app.obs.context import conversation_id_var, correlation_id_var, request_id_var

log = logging.getLogger("ai.usage")


@dataclass(frozen=True)
class UsageRecord:
    operation: str  # generate | embed
    model: str
    tokens_in: int | None
    tokens_out: int | None
    latency_ms: int
    outcome: str  # ok | error code
    tier: str | None = None
    intent: str | None = None
    customer_hash: str | None = None
    cost_estimate: float | None = None
    cost_currency: str = "USD"
    cost_is_estimate: bool = True
    correlation_id: str | None = field(default_factory=correlation_id_var.get)
    request_id: str | None = field(default_factory=request_id_var.get)
    conversation_id: str | None = field(default_factory=conversation_id_var.get)
    ts: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


def estimate_cost(model: str, tokens_in: int | None, tokens_out: int | None,
                  price_table: dict[str, dict[str, float]]) -> float | None:
    price = price_table.get(model)
    if price is None or tokens_in is None:
        return None
    return round(tokens_in / 1_000_000 * price["input_per_1m"] + (tokens_out or 0) / 1_000_000 * price["output_per_1m"], 8)


class UsageSink(Protocol):
    async def write(self, record: UsageRecord) -> None: ...


class UsageRecorder:
    """Logs every record and, when a sink (the AI database) is available, persists it. Never raises into a request."""

    def __init__(self, price_table: dict[str, dict[str, float]], sink: UsageSink | None = None):
        self._prices = price_table
        self._sink = sink

    async def record(self, *, operation: str, model: str, tokens_in: int | None, tokens_out: int | None,
                     latency_ms: int, outcome: str = "ok", tier: str | None = None, intent: str | None = None,
                     customer_hash: str | None = None) -> UsageRecord:
        record = UsageRecord(
            operation=operation, model=model, tokens_in=tokens_in, tokens_out=tokens_out, latency_ms=latency_ms,
            outcome=outcome, tier=tier, intent=intent, customer_hash=customer_hash,
            cost_estimate=estimate_cost(model, tokens_in, tokens_out, self._prices),
        )
        log.info("model usage", extra={
            "operation": operation, "model": model, "tokens_in": tokens_in, "tokens_out": tokens_out,
            "latency_ms": latency_ms, "outcome": outcome, "tier": tier, "intent": intent,
            "cost_estimate": record.cost_estimate, "cost_currency": record.cost_currency, "cost_is_estimate": True,
            "customer_hash": customer_hash,
        })
        metrics.LLM_LATENCY.labels(model, operation).observe(latency_ms / 1000)
        if tokens_in:
            metrics.LLM_TOKENS.labels(model, "in").inc(tokens_in)
        if tokens_out:
            metrics.LLM_TOKENS.labels(model, "out").inc(tokens_out)
        if record.cost_estimate:
            metrics.COST_USD.labels(model).inc(record.cost_estimate)
        if self._sink is not None:
            try:
                await self._sink.write(record)
            except Exception:  # usage accounting must never break a customer request
                log.warning("usage record could not be persisted", exc_info=True)
        return record
