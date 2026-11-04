"""The only surface the rest of the service sees from a model provider. Swapping provider means one new class."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Literal, Protocol

Tier = Literal["intent", "reasoning"]  # intent = cheap structured output (T1); reasoning = synthesis (T2)


class LLMError(Exception):
    """A model call failed. `code` is stable and safe to expose; the provider's raw message is not."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class LLMResult:
    text: str
    model: str
    tokens_in: int | None
    tokens_out: int | None
    latency_ms: int


@dataclass(frozen=True)
class EmbeddingResult:
    vectors: list[list[float]]
    model: str
    dimension: int
    latency_ms: int


class LLMClient(Protocol):
    async def generate(self, prompt: str, *, tier: Tier = "reasoning", system: str | None = None,
                       json_mode: bool = False, max_tokens: int | None = None,
                       temperature: float | None = None, intent: str | None = None,
                       customer_hash: str | None = None) -> LLMResult: ...


class EmbeddingClient(Protocol):
    async def embed(self, texts: list[str], *, task_type: Literal["RETRIEVAL_DOCUMENT", "RETRIEVAL_QUERY"],
                    customer_hash: str | None = None) -> EmbeddingResult: ...
