"""Timeout/retry/circuit-breaker wrapper around any LLMClient + EmbeddingClient.

Only idempotent model calls are retried (a generate/embed has no side effects). The breaker opens after N consecutive
failures and stays open for a cool-down; while open every call fails fast with AI_UNAVAILABLE so a Gemini outage cannot
pile up slow requests, and Tier-0 (no LLM) flows keep working.
"""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Literal

from app.llm.base import EmbeddingResult, LLMError, LLMResult, Tier
from app.obs import metrics

log = logging.getLogger(__name__)
_RETRYABLE = {"LLM_TIMEOUT", "LLM_ERROR"}
# Errors that mean "the AI is not usable" and should trip the breaker (as opposed to a bad single request).
_TRIPS = {"LLM_TIMEOUT", "LLM_ERROR", "LLM_QUOTA", "LLM_AUTH", "LLM_PERMISSION_DENIED", "LLM_MODEL_NOT_FOUND",
          "LLM_NOT_CONFIGURED", "LLM_BAD_RESPONSE"}


class CircuitBreaker:
    def __init__(self, failures: int, cooldown_s: float, clock=time.monotonic):
        self._threshold = failures
        self._cooldown = cooldown_s
        self._clock = clock
        self._failures = 0
        self._opened_at: float | None = None
        self._probing = False

    @property
    def state(self) -> str:
        if self._opened_at is None:
            return "closed"
        return "half_open" if self._clock() - self._opened_at >= self._cooldown else "open"

    def allow(self) -> bool:
        state = self.state
        if state == "closed":
            return True
        if state == "half_open" and not self._probing:
            self._probing = True  # exactly one trial call
            return True
        return False

    def success(self) -> None:
        self._failures, self._opened_at, self._probing = 0, None, False
        metrics.BREAKER_STATE.set(0)

    def failure(self) -> None:
        self._failures += 1
        self._probing = False
        if self._failures >= self._threshold or self._opened_at is not None:
            if self._opened_at is None:
                log.error("LLM circuit breaker opened", extra={"failures": self._failures})
            self._opened_at = self._clock()
            metrics.BREAKER_STATE.set(1)


class ResilientLLM:
    def __init__(self, inner, breaker: CircuitBreaker, retries: int = 1, retry_delay_s: float = 0.4):
        self._inner = inner
        self.breaker = breaker
        self._retries = retries
        self._delay = retry_delay_s

    @property
    def project(self) -> str | None:
        return getattr(self._inner, "project", None)

    async def _guarded(self, call):
        if not self.breaker.allow():
            raise LLMError("AI_UNAVAILABLE", "The AI service is temporarily unavailable")
        attempt = 0
        while True:
            try:
                result = await call()
            except LLMError as exc:
                if exc.code in _RETRYABLE and attempt < self._retries:
                    attempt += 1
                    await asyncio.sleep(self._delay * attempt)
                    continue
                if exc.code in _TRIPS:
                    self.breaker.failure()
                raise
            self.breaker.success()
            return result

    async def generate(self, prompt: str, *, tier: Tier = "reasoning", **kw) -> LLMResult:
        return await self._guarded(lambda: self._inner.generate(prompt, tier=tier, **kw))

    async def embed(self, texts: list[str], *, task_type: Literal["RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY"], **kw) -> EmbeddingResult:
        return await self._guarded(lambda: self._inner.embed(texts, task_type=task_type, **kw))
