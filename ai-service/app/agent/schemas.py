"""The structured response of the assistant. Everything under "verified" (products, orders, tracking, ...) comes straight from
DSports tool results; `message` is the explanation, labelled by `messageSource` so the UI can tell AI text from data."""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatRequest(Camel):
    message: str = Field(min_length=1, max_length=4000)  # the guard enforces the real limit with a friendly refusal
    conversation_id: str | None = Field(None, max_length=64)
    # The product the customer chose in the UI ("Ask AI"). Only the id is accepted: name, price and stock are always re-read from DSports.
    selected_product_id: str | None = Field(None, max_length=64)
    clear_product_context: bool = False
    # An option the customer tapped in the previous response. Opaque: the service resolves it to the exact step it offered.
    choice_id: str | None = Field(None, max_length=64)
    # The customer's OWN support case this conversation is about. Re-read from DSports with the customer's token (someone else's case is a 404 there).
    case_ref: str | None = Field(None, max_length=40)


class ConfirmRequest(Camel):
    confirmation_token: str = Field(min_length=10, max_length=2000)
    conversation_id: str | None = Field(None, max_length=64)


class ActionPrompt(Camel):
    type: Literal["confirm"] = "confirm"
    tool: str
    label: str
    summary: str
    confirmation_token: str
    expires_at: str


class Source(Camel):
    source: str
    title: str | None = None
    page: int | None = None
    document_type: str | None = None
    chunk_id: str
    snippet: str | None = None


class Meta(Camel):
    request_id: str | None = None
    correlation_id: str | None = None
    tools: list[dict[str, Any]] = []
    llm_calls: int = 0
    tokens_in: int = 0
    tokens_out: int = 0
    latency_ms: int = 0
    cost_estimate_usd: float | None = None
    models: list[str] = []
    grounding: dict[str, Any] = {}
    retrieval: dict[str, Any] = {}
    route_source: str | None = None
    slots: dict[str, Any] = {}


class Suggestion(Camel):
    label: str
    message: str | None = None
    choice_id: str | None = None


class RecommendationsResponse(Camel):
    title: str
    personalized: bool = False
    because: list[str] = []
    items: list[dict[str, Any]] = []
    bundle: dict[str, Any] | None = None
    # Scenario-based pipeline only (absent for the legacy cart/history flow)
    scenario: str | None = None
    reference: dict[str, Any] | None = None
    budget: dict[str, Any] | None = None
    profile: dict[str, Any] | None = None
    evidence: dict[str, Any] | None = None
    message: str | None = None
    suggested_scenario: str | None = None
    personalization_note: str | None = None
    meta: dict[str, Any] | None = None


class ProductRef(Camel):
    product_id: str
    name: str


class ProductContext(Camel):
    """What the conversation is currently about (for the UI indicator). Names come from DSports, never from the model."""
    selected_product: ProductRef | None = None
    comparison_products: list[ProductRef] = []


class ChatResponse(Camel):
    conversation_id: str
    status: Literal["ok", "refused", "needs_login", "ai_unavailable", "error"] = "ok"
    intent: str
    tier: int = 0
    message: str
    message_source: Literal["ai", "deterministic"] = "deterministic"
    products: list[dict[str, Any]] = []
    comparison: dict[str, Any] | None = None
    orders: list[dict[str, Any]] = []
    tracking: dict[str, Any] | None = None
    cart: dict[str, Any] | None = None
    payments: list[dict[str, Any]] = []
    refunds: list[dict[str, Any]] = []
    eligibility: dict[str, Any] | None = None
    reviews: dict[str, Any] | None = None
    type: str = "TEXT"
    recommendations: dict[str, Any] | None = None
    selection: dict[str, Any] | None = None
    suggestions: list[Suggestion] = []
    context: ProductContext = ProductContext()
    action_result: dict[str, Any] | None = None
    actions: list[ActionPrompt] = []
    sources: list[Source] = []
    requires_confirmation: bool = False
    confirmation_token: str | None = None
    disclaimers: list[str] = []
    support: dict[str, Any] | None = None   # investigation (verified / possible / unverified), resolution, or a case card exactly as the backend returned it
    error_code: str | None = None
    meta: Meta = Meta()
