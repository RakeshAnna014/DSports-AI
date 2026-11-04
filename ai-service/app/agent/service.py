"""Runs one assistant turn: conversation state, the LangGraph agent with limits, and the structured response."""
from __future__ import annotations

import asyncio
import logging
import time
import uuid
from datetime import datetime, timezone
from typing import Any

from app.agent import offers, orders, product_context
from app.agent.recommend import recommend as recommend_products
from app.agent.response_kind import response_type
from app.agent.suggestions import build_suggestions
from app.reco.engine import RecoRequest, recommend_with_scenario
from app.agent.graph import build_customer_agent
from app.agent.runtime import Runtime, Turn
from app.agent.schemas import ChatRequest, ChatResponse, ConfirmRequest, Meta, ProductContext, ProductRef, Suggestion
from app.auth.jwt import Principal
from app.errors import ApiException
from app.obs import metrics
from app.obs.context import conversation_id_var, correlation_id_var, request_id_var
from app.router.intents import SUPPORT, Intent, Route
from app.services import Services
from app.store.state import GUEST, ConversationState
from app.support import flow as support_flow
from app.tools.support_tools import normalize_case_ref
from app.tools.registry import ToolContext

log = logging.getLogger(__name__)


class RateLimiter:
    """Per-customer sliding window (in-process, Phase 1). Guests share one bucket per conversation id."""

    def __init__(self, per_minute: int):
        self._limit = per_minute
        self._hits: dict[str, list[float]] = {}

    def allow(self, key: str) -> bool:
        now = time.monotonic()
        window = [t for t in self._hits.get(key, []) if now - t < 60]
        if len(window) >= self._limit:
            self._hits[key] = window
            return False
        window.append(now)
        self._hits[key] = window
        return True


def make_summary(conv: ConversationState) -> str:
    """Rolling summary, built by rules (no model call), kept under ~150 tokens. It never contains prices or stock."""
    s = conv.slots.get("search", {})
    bits = []
    wants = " ".join(str(x) for x in (s.get("skill_level"), "lightweight" if s.get("weight_pref") == "light" else "heavier" if s.get("weight_pref") == "heavy" else None,
                                      s.get("sport"), s.get("category"), s.get("brand")) if x)
    if wants:
        bits.append(f"Looking for: {wants}")
    if s.get("max_price"):
        bits.append(f"budget up to {int(s['max_price'])} INR")
    if s.get("min_price"):
        bits.append(f"at least {int(s['min_price'])} INR")
    if conv.last_products:
        bits.append("Shown: " + "; ".join(p["name"] for p in conv.last_products[:5]))
    if conv.selected_product:
        bits.append(f"Asking about: {conv.selected_product['name']}")
    if conv.last_order:
        bits.append(f"Last order discussed: {conv.last_order.get('orderNumber')}")
    if conv.slots.get("return_candidate"):
        bits.append(f"Return candidate: {conv.slots['return_candidate']['product_name']}")
    return (". ".join(bits) + ("." if bits else ""))[:600]


class ChatService:
    def __init__(self, services: Services):
        self.s = services
        self.agent = build_customer_agent()
        self.limiter = RateLimiter(services.settings.rate_limit_per_min)

    # ------------------------------------------------------------------ helpers
    @staticmethod
    def _owner(principal: Principal | None) -> str:
        return principal.customer_hash if principal else GUEST

    async def _conversation(self, principal: Principal | None, conversation_id: str | None) -> ConversationState:
        owner = self._owner(principal)
        if conversation_id:
            existing = await self.s.state.load(conversation_id, owner)
            if existing is not None:
                return existing
            # Unknown, expired or someone else's id: start a fresh conversation (the other customer's is never revealed).
        return ConversationState(conversation_id=str(uuid.uuid4()), owner=owner)

    def _runtime(self, principal: Principal | None, conv: ConversationState) -> Runtime:
        ctx = ToolContext(principal=principal, dsports=self.s.dsports, settings=self.s.settings, services=self.s,
                          max_calls=self.s.settings.max_tool_calls)
        return Runtime(services=self.s, ctx=ctx, conv=conv, principal=principal)

    # ------------------------------------------------------------------ product context ("Ask AI" on a product)
    @staticmethod
    async def _apply_product_context(rt: Runtime, req: ChatRequest) -> None:
        """The UI may name the product the customer is asking about. Only its id is accepted: the product is re-read from DSports (so an
        unknown, inactive or forged id is ignored) and its name, price and stock never come from the request."""
        if req.clear_product_context:
            rt.conv.selected_product, rt.conv.comparison_ids = None, []
        if not req.selected_product_id:
            return
        try:
            product_id = str(uuid.UUID(req.selected_product_id))
        except ValueError:
            rt.turn.disclaimers.append("I could not use that product selection.")
            return
        if rt.conv.selected_product and rt.conv.selected_product["id"] == product_id:
            return
        result = await rt.call("get_product", {"product_id": product_id})
        if result.ok:
            product_context.select_product(rt.conv, result.data["product"])
        else:
            rt.turn.disclaimers.append("I could not find that product in the catalogue, so I ignored the selection.")

    @staticmethod
    def _apply_choice(rt: Runtime, req: ChatRequest) -> None:
        """A tapped option: resolve it from the offers THIS conversation was given in its previous response."""
        if not req.choice_id:
            return
        offer = offers.resolve(rt.conv.slots, req.choice_id)
        if offer is None:
            rt.turn.disclaimers.append("That option is no longer available, so I treated your message as a new question.")
            return
        rt.forced_route = Route(Intent(offer["intent"]), 0, dict(offer["slots"]))

    # ------------------------------------------------------------------ recommendations (no model; real data and real reasons)
    async def recommend(self, principal: Principal | None, product_ids: list[str], limit: int, scenario_request: RecoRequest | None = None) -> dict[str, Any]:
        key = self._owner(principal) if principal else "guest:recommend"
        if not self.limiter.allow(key):
            raise ApiException(429, "RATE_LIMITED", "You're sending requests too quickly. Please wait a moment.")
        if scenario_request is not None:
            rt = self._runtime(principal, ConversationState(conversation_id=str(uuid.uuid4()), owner=self._owner(principal)))
            return await recommend_with_scenario(rt, scenario_request)
        ids = []
        for raw in product_ids[:3]:
            try:
                ids.append(str(uuid.UUID(raw)))
            except ValueError:
                continue
        rt = self._runtime(principal, ConversationState(conversation_id=str(uuid.uuid4()), owner=self._owner(principal)))
        return await recommend_products(rt, ids, max(1, min(limit, 6)), exclude_ids=ids)

    # ------------------------------------------------------------------ checkout offers (no model; the backend decides, this explains)
    async def recommend_offers(self, principal: Principal, checkout_id: str) -> dict[str, Any]:
        if not self.limiter.allow(self._owner(principal)):
            raise ApiException(429, "RATE_LIMITED", "You're sending requests too quickly. Please wait a moment.")
        from app.agent.promo_offers import recommend_offers
        rt = self._runtime(principal, ConversationState(conversation_id=str(uuid.uuid4()), owner=self._owner(principal)))
        rt.ctx.max_calls = max(rt.ctx.max_calls, 30)
        return await recommend_offers(rt, checkout_id)

    # ------------------------------------------------------------------ support assistant linked to one of the customer's cases
    CASE_CONVERSATION_TTL_HOURS = 24.0 * 30      # a case-linked conversation can be resumed from the case page for a month
    TRANSCRIPT_LIMIT = 40

    async def _apply_case_context(self, rt: Runtime, req: ChatRequest) -> None:
        """The customer opened the assistant from one of their cases. The case is re-read from DSports with THEIR token (someone else's case is a
        404 there and is never revealed), then becomes the conversation's case: status, message and escalation questions refer to it, no second case
        is opened for the same problem, and the conversation can be resumed from the case page."""
        if not req.case_ref:
            return
        ref = normalize_case_ref(req.case_ref)
        if rt.principal is None or ref is None:
            rt.turn.disclaimers.append("I could not use that case, so I treated this as a general question.")
            return
        result = await rt.call("get_support_case", {"case_ref": ref})
        if not result.ok:
            rt.turn.disclaimers.append("I could not open that case, so I treated this as a general question.")
            return
        case = result.data
        first_link = rt.conv.slots.get("case_ref") != case["case_number"]
        rt.case = case
        rt.conv.slots["case_ref"] = case["case_number"]
        rt.conv.ttl_hours = self.CASE_CONVERSATION_TTL_HOURS
        support_flow.link_case(rt, case)
        if first_link:
            try:
                await self.s.state.audit(customer_hash=rt.customer_hash, action="SUPPORT_ASSIST_CASE_LINKED", outcome="OK", conversation_id=rt.conv.conversation_id,
                                         detail={"case_number": case["case_number"], "status": case["status"]})
            except Exception:  # noqa: BLE001 - informational record; the chat itself does not depend on it
                log.warning("could not audit the case link", exc_info=True)

    @staticmethod
    def _record_transcript(rt: Runtime, response_type_name: str) -> None:
        """Case-linked conversations keep a bounded, plain-text transcript so the customer can resume them. Text only: no tokens, ids or tool data."""
        conv = rt.conv
        if not conv.slots.get("case_ref"):
            return
        now = datetime.now(timezone.utc).isoformat()
        log_ = list(conv.slots.get("transcript") or [])
        if rt.user_message:
            log_.append({"role": "user", "text": rt.user_message[:1500], "at": now})
        log_.append({"role": "assistant", "text": (rt.turn.text or rt.turn.fallback_text or "")[:1500], "at": now, "kind": response_type_name})
        conv.slots["transcript"] = log_[-ChatService.TRANSCRIPT_LIMIT:]

    async def case_conversation(self, principal: Principal, case_ref: str) -> dict[str, Any]:
        """The customer's latest conversation about one of their own cases (to resume it). The case must be theirs: DSports decides that."""
        ref = normalize_case_ref(case_ref)
        if ref is None:
            raise ApiException(400, "VALIDATION_ERROR", "caseRef is not a valid case number")
        if not self.limiter.allow(self._owner(principal)):
            raise ApiException(429, "RATE_LIMITED", "You're sending requests too quickly. Please wait a moment.")
        rt = self._runtime(principal, ConversationState(conversation_id=str(uuid.uuid4()), owner=self._owner(principal)))
        if not (await rt.call("get_support_case", {"case_ref": ref})).ok:
            raise ApiException(404, "NOT_FOUND", "That case could not be found.")
        conv = await self.s.state.find_by_case(self._owner(principal), normalize_case_ref(ref) or ref)
        if conv is None:
            return {"caseRef": ref, "conversationId": None, "transcript": []}
        return {"caseRef": ref, "conversationId": conv.conversation_id, "transcript": list(conv.slots.get("transcript") or [])}

    # ------------------------------------------------------------------ chat
    async def chat(self, principal: Principal | None, req: ChatRequest) -> ChatResponse:
        started = time.perf_counter()
        key = self._owner(principal) if principal else f"guest:{req.conversation_id or 'anon'}"
        if not self.limiter.allow(key):
            raise ApiException(429, "RATE_LIMITED", "You're sending messages too quickly. Please wait a moment.")
        conv = await self._conversation(principal, req.conversation_id)
        conversation_id_var.set(conv.conversation_id)
        rt = self._runtime(principal, conv)
        rt.user_message = req.message
        await self._apply_product_context(rt, req)
        await self._apply_case_context(rt, req)
        self._apply_choice(rt, req)
        route_intent, outcome = Intent.OUT_OF_SCOPE.value, "ok"
        try:
            state = await asyncio.wait_for(
                self.agent.ainvoke({"message": req.message}, config={"recursion_limit": self.s.settings.max_iterations,
                                                                   "configurable": {"rt": rt}}),
                timeout=self.s.settings.request_timeout_s)
            route = state.get("route")
            route_intent = route.intent.value if route else route_intent
            slots = dict(state.get("slots") or (route.slots if route else {}))   # the effective (merged) search slots when a search ran
        except asyncio.TimeoutError:
            rt.turn = Turn(text="That took too long. Please try again.", status="error", error_code="TIMEOUT")
            rt.turn.fallback_text, slots = rt.turn.text, {}
        except Exception:
            log.error("agent failed", exc_info=True)
            rt.turn = Turn(text="Something went wrong on my side. Please try again.", status="error", error_code="AGENT_ERROR")
            rt.turn.fallback_text, slots = rt.turn.text, {}
        tier = 2 if rt.highest_tier >= 2 else (1 if rt.highest_tier == 1 else 0)
        response = await self._finish(rt, route_intent, tier, slots, started)
        outcome = response.status if response.status != "ok" else ("fallback" if response.meta.grounding.get("fallback") else "ok")
        metrics.REQUESTS.labels(route_intent, str(tier), outcome).inc()
        log.info("assistant turn", extra={"intent": route_intent, "tier": tier, "status": response.status, "tools": len(rt.ctx.trace),
                                          "llm_calls": rt.llm_calls, "tokens_in": rt.tokens_in, "tokens_out": rt.tokens_out,
                                          "latency_ms": response.meta.latency_ms, "grounded_fallback": response.meta.grounding.get("fallback", False),
                                          "error_code": response.error_code, "customer_hash": rt.customer_hash,
                                          "cost_estimate_usd": rt.cost, "source": rt.route_source})
        return response

    # ------------------------------------------------------------------ explicit confirmation (UI button)
    async def confirm(self, principal: Principal, req: ConfirmRequest) -> ChatResponse:
        started = time.perf_counter()
        if not self.limiter.allow(principal.customer_hash):
            raise ApiException(429, "RATE_LIMITED", "You're sending requests too quickly. Please wait a moment.")
        conv = await self._conversation(principal, req.conversation_id)
        conversation_id_var.set(conv.conversation_id)
        rt = self._runtime(principal, conv)
        outcome = await self.s.executor.confirm(principal, req.confirmation_token, rt.ctx)
        if conv.pending_token == req.confirmation_token or outcome.code in ("CONFIRMATION_ALREADY_USED", "CONFIRMATION_EXPIRED"):
            conv.pending_nonce = conv.pending_token = None
        orders.apply_outcome(rt, outcome)
        if outcome.code in ("CONFIRMATION_INVALID", "CONFIRMATION_EXPIRED", "CONFIRMATION_ALREADY_USED"):
            rt.turn.status = "error"
        response = await self._finish(rt, "CONFIRM_ACTION", 0, {}, started)
        metrics.REQUESTS.labels("CONFIRM_ACTION", "0", "ok" if outcome.ok else (outcome.code or "error")).inc()
        return response

    # ------------------------------------------------------------------ response assembly
    @staticmethod
    def _context(conv: ConversationState) -> ProductContext:
        names = {p["id"]: p["name"] for p in conv.last_products}
        if conv.selected_product:
            names[conv.selected_product["id"]] = conv.selected_product["name"]
        return ProductContext(
            selected_product=ProductRef(product_id=conv.selected_product["id"], name=conv.selected_product["name"]) if conv.selected_product else None,
            comparison_products=[ProductRef(product_id=i, name=names[i]) for i in conv.comparison_ids if i in names])

    async def _finish(self, rt: Runtime, intent: str, tier: int, slots: dict[str, Any], started: float) -> ChatResponse:
        conv, t = rt.conv, rt.turn
        conv.turns += 1
        build_suggestions(rt, intent)
        support_flow.case_suggestions(rt)
        self._record_transcript(rt, response_type(t, intent))
        conv.slots["offers"] = t.offers          # only this response's options stay valid
        conv.rolling_summary = make_summary(conv)
        await self.s.state.save(conv)
        if rt.principal is not None and intent in {i.value for i in SUPPORT}:
            try:   # one non-content row per support turn: the insights count conversations, never read what was said
                await self.s.state.audit(customer_hash=rt.principal.customer_hash, action="SUPPORT_TURN", outcome=intent, conversation_id=conv.conversation_id, detail=None)
            except Exception:  # noqa: BLE001 - measuring must never break the answer
                log.warning("could not record a support turn", exc_info=True)
        meta = Meta(request_id=request_id_var.get(), correlation_id=correlation_id_var.get(), tools=rt.ctx.trace, llm_calls=rt.llm_calls,
                    tokens_in=rt.tokens_in, tokens_out=rt.tokens_out, latency_ms=int((time.perf_counter() - started) * 1000),
                    cost_estimate_usd=rt.cost, models=rt.models, grounding=t.grounding, retrieval=t.retrieval, route_source=rt.route_source,
                    slots={k: v for k, v in slots.items() if k not in ("question",)})
        return ChatResponse(
            conversation_id=conv.conversation_id, status=t.status, intent=intent, tier=tier, message=t.text or t.fallback_text,
            message_source=t.message_source, products=t.products, comparison=t.comparison, orders=t.orders, tracking=t.tracking,
            cart=t.cart, reviews=t.reviews, type=response_type(t, intent), selection=t.selection, recommendations=t.recommendations,
            suggestions=[Suggestion(label=s["label"], message=s.get("message"), choice_id=s.get("choice_id")) for s in t.suggestions],
            context=self._context(conv), payments=t.payments, refunds=t.refunds, eligibility=t.eligibility, action_result=t.action_result,
            actions=t.actions, sources=t.sources, requires_confirmation=bool(t.actions), confirmation_token=t.confirmation_token,
            disclaimers=list(dict.fromkeys(t.disclaimers)), support=t.support, error_code=t.error_code, meta=meta)
