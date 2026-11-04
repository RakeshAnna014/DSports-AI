"""Per-request runtime: budgets, usage accounting, the accumulating Turn, and the LLM gateway used by the graph nodes."""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any

from app.auth.jwt import Principal
from app.llm.base import LLMError, LLMResult, Tier
from app.obs.usage import estimate_cost
from app.store.state import ConversationState
from app.tools.registry import ToolContext, ToolResult

log = logging.getLogger(__name__)
DISCLAIMER_AI = "AI-written explanation. Prices, stock and order facts above are verified from DSports."


class BudgetExceeded(Exception):
    pass


@dataclass
class Turn:
    """Everything this request will return, accumulated by the nodes."""
    text: str = ""
    fallback_text: str = ""                 # deterministic answer from the same data; used if the model text is unusable
    message_source: str = "deterministic"
    status: str = "ok"
    error_code: str | None = None
    products: list[dict[str, Any]] = field(default_factory=list)
    comparison: dict[str, Any] | None = None
    orders: list[dict[str, Any]] = field(default_factory=list)
    tracking: dict[str, Any] | None = None
    cart: dict[str, Any] | None = None
    payments: list[dict[str, Any]] = field(default_factory=list)
    refunds: list[dict[str, Any]] = field(default_factory=list)
    eligibility: dict[str, Any] | None = None
    reviews: dict[str, Any] | None = None
    selection: dict[str, Any] | None = None
    recommendations: dict[str, Any] | None = None
    suggestions: list[dict[str, Any]] = field(default_factory=list)
    offers: dict[str, Any] = field(default_factory=dict)
    action_result: dict[str, Any] | None = None
    actions: list[dict[str, Any]] = field(default_factory=list)
    sources: list[dict[str, Any]] = field(default_factory=list)
    disclaimers: list[str] = field(default_factory=list)
    extra_facts: list[Any] = field(default_factory=list)   # proposal facts etc. that the text may legitimately mention
    retrieval: dict[str, Any] = field(default_factory=dict)
    grounding: dict[str, Any] = field(default_factory=dict)
    confirmation_token: str | None = None
    support: dict[str, Any] | None = None   # the structured support payload (investigation, case card, case list) for the UI


@dataclass
class Runtime:
    services: Any
    ctx: ToolContext
    conv: ConversationState
    principal: Principal | None
    turn: Turn = field(default_factory=Turn)
    started: float = field(default_factory=time.perf_counter)
    llm_calls: int = 0
    tokens_in: int = 0
    tokens_out: int = 0
    models: list[str] = field(default_factory=list)
    highest_tier: int = 0
    cost: float | None = None
    route_source: str | None = None
    forced_route: Any = None        # a Route resolved from an offer the customer tapped (ids come from the service's own offer)
    user_message: str = ""          # what the customer typed this turn (for the case-linked transcript)
    case: dict[str, Any] | None = None   # the linked support case, re-read from DSports this turn (never trusted from stored state)

    @property
    def settings(self):
        return self.services.settings

    @property
    def customer_hash(self) -> str | None:
        return self.principal.customer_hash if self.principal else None

    def note_route_usage(self, tokens_in: int, tokens_out: int, model: str, calls: int) -> None:
        self.tokens_in += tokens_in
        self.tokens_out += tokens_out
        self.llm_calls += calls
        if calls and model not in self.models:
            self.models.append(model)
        self._cost(model, tokens_in, tokens_out)

    def _cost(self, model: str, tin: int | None, tout: int | None) -> None:
        c = estimate_cost(model, tin, tout, self.settings.price_table)
        if c is not None:
            self.cost = (self.cost or 0.0) + c

    async def ask(self, prompt: str, *, tier: Tier = "reasoning", system: str | None = None, intent: str | None = None,
                  json_mode: bool = False, max_tokens: int | None = None, temperature: float | None = None) -> LLMResult:
        """The only way nodes reach the model: enforces the per-request token budget and records usage."""
        if self.tokens_in + self.tokens_out >= self.settings.request_token_budget:
            raise BudgetExceeded()
        result = await self.services.llm.generate(prompt, tier=tier, system=system, json_mode=json_mode, max_tokens=max_tokens,
                                                  temperature=temperature, intent=intent, customer_hash=self.customer_hash)
        self.tokens_in += result.tokens_in or 0
        self.tokens_out += result.tokens_out or 0
        self.llm_calls += 1
        if result.model not in self.models:
            self.models.append(result.model)
        self.highest_tier = max(self.highest_tier, 1 if tier == "intent" else 2)
        self._cost(result.model, result.tokens_in, result.tokens_out)
        return result

    async def call(self, tool: str, args: dict[str, Any] | None = None) -> ToolResult:
        return await self.services.registry.call(tool, args or {}, self.ctx)

    @property
    def llm_available(self) -> bool:
        return self.services.llm.breaker.state != "open"
