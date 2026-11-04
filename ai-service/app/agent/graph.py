"""The Customer Agent as one LangGraph workflow:

    START -> guard_in -> route -+-> shopping  (subgraph: search | compare/advice | knowledge)
                                +-> orders_returns (subgraph: orders, tracking, payments, refunds, cart, returns, reorder, confirm)
                                +-> support  (subgraph: policy/FAQ/warranty, refund questions)
                                +-> smalltalk
                                `-> (blocked / needs sign-in) ------------------------------+
                                                                                              v
                                                                  guard_out -> END   (grounding check on model text)

One agent, three subgraphs where there is a real domain boundary; simple capabilities are typed tools, not agents. The graph
carries only a minimal state; the per-request runtime (tools, budgets, conversation) travels in the run config.
"""
from __future__ import annotations

import logging
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.agent import orders, product_context, recommend, shopping, support, templates
from app.agent.prompts import RULES_ID
from app.agent.runtime import Runtime
from app.guard.grounding import check_grounding, collect_facts
from app.guard.input import check_input
from app.llm.base import LLMError
from app.obs import metrics
from app.router import tier0
from app.router.extract import extract_shopping_slots
from app.router.intents import CONFIRM, GUEST_INTENTS, ORDERS, SHOPPING, SUPPORT, Intent, Route
from app.support import flow as support_flow

log = logging.getLogger(__name__)

HELP_TEXT = ("I can help you find and compare cricket and other sports gear, answer questions about product care, shipping, returns and "
             "refunds, and check your orders, tracking, payments and refunds. What would you like to do?")
SIGN_IN_TEXT = "Please sign in to use that. I can still help you find products and answer questions about DSports without an account."


class AgentState(TypedDict, total=False):
    message: str
    route: Route
    slots: dict[str, Any]
    data: Any
    done: bool


def _rt(config) -> Runtime:
    return config["configurable"]["rt"]


# ------------------------------------------------------------------------------------------------ top level

async def guard_in(state: AgentState, config) -> AgentState:
    rt = _rt(config)
    verdict = check_input(state["message"])
    if verdict.allowed:
        return {"done": False}
    rt.turn.status = "refused" if verdict.category not in ("empty", "too_long") else "ok"
    rt.turn.text = rt.turn.fallback_text = verdict.refusal or ""
    rt.turn.error_code = f"GUARD_{verdict.category.upper()}" if verdict.category else None
    return {"route": Route(Intent.BLOCKED, 0), "done": True}


async def route_node(state: AgentState, config) -> AgentState:
    rt = _rt(config)
    message = state["message"]
    route = rt.forced_route or tier0.classify(message, rt.conv, authenticated=rt.principal is not None, known_products=await rt.services.known_products())
    if route is None:
        try:
            route = await rt.services.tier1.classify(message, rt.conv, rt.customer_hash)
            rt.note_route_usage(route.tokens_in, route.tokens_out, rt.settings.intent_model, 0 if route.source == "cache" else 1)
            if route.source != "cache":
                rt.highest_tier = max(rt.highest_tier, 1)
        except LLMError as exc:
            # The model is unavailable: keep what rules can still do (a plain filtered search); otherwise say so.
            slots = extract_shopping_slots(message)
            if slots:
                route = Route(Intent.PRODUCT_SEARCH, 0, slots, source="fallback")
                rt.turn.disclaimers.append("The AI assistant is limited right now, so I used a simple search.")
            else:
                rt.turn.status, rt.turn.error_code = "ai_unavailable", "AI_UNAVAILABLE"
                rt.turn.text = rt.turn.fallback_text = ("The AI assistant is temporarily unavailable, so I can't work out that request. "
                                                         "You can still ask me to show your orders or cart, track an order, or search by sport "
                                                         "and product type (for example \"cricket bats under 8000\").")
                return {"route": Route(Intent.OUT_OF_SCOPE, 1, source="fallback"), "done": True}
            log.warning("tier-1 unavailable", extra={"code": exc.code})
    rt.route_source = route.source
    rt.conv.intent = route.intent.value
    if route.intent not in GUEST_INTENTS and rt.principal is None:
        rt.turn.status = "needs_login"
        rt.turn.text = rt.turn.fallback_text = SIGN_IN_TEXT
        return {"route": route, "done": True}
    return {"route": route, "done": False}


def after_guard(state: AgentState) -> str:
    return "guard_out" if state.get("done") else "route"


def dispatch(state: AgentState) -> str:
    if state.get("done"):
        return "guard_out"
    intent = state["route"].intent
    if intent in SHOPPING:
        return "shopping"
    if intent in ORDERS or intent in CONFIRM:
        return "orders_returns"
    if intent in SUPPORT:
        return "support"
    return "smalltalk"


async def smalltalk(state: AgentState, config) -> AgentState:
    rt = _rt(config)
    greeting = state["route"].intent == Intent.SMALLTALK
    rt.turn.text = rt.turn.fallback_text = ("Hello! " if greeting else "") + HELP_TEXT
    return {}


async def guard_out(state: AgentState, config) -> AgentState:
    """Verify the model's text against THIS turn's tool results; replace it with the deterministic answer if anything is unsupported."""
    rt = _rt(config)
    t = rt.turn
    leaked = RULES_ID.lower() in t.text.lower()   # the model repeated our system prompt
    if t.message_source == "ai":
        facts = collect_facts([*rt.ctx.results, *t.extra_facts])
        report = check_grounding(t.text, facts, await rt.services.known_products())
        t.grounding = {"checked": report.checked, "violations": [{"kind": v.kind, "text": v.text} for v in report.violations],
                       "fallback": False, "model_text_ok": report.ok and not leaked}
        if leaked or not report.ok:
            metrics.GROUNDING_FALLBACKS.inc()
            log.warning("model answer failed grounding; using deterministic answer",
                        extra={"violations": [(v.kind, v.text) for v in report.violations], "leak": leaked})
            t.text, t.message_source = t.fallback_text or HELP_TEXT, "deterministic"
            t.grounding["fallback"] = True
            t.disclaimers = [d for d in t.disclaimers if d != "AI-written explanation. Prices, stock and order facts above are verified from DSports."]
            t.disclaimers.append("An AI explanation was replaced because it contained something I could not verify; showing verified data only.")
    return {}


# ------------------------------------------------------------------------------------------------ shopping subgraph

async def sh_search(state: AgentState, config) -> AgentState:
    rt = _rt(config)
    out = await shopping.search(rt, state["route"])
    return {"slots": out["slots"], "data": out["data"]}


async def sh_explain(state: AgentState, config) -> AgentState:
    if state["route"].slots.get("plain"):   # a plain keyword search is answered by the deterministic result list: no model call
        rt = _rt(config)
        rt.turn.text = rt.turn.fallback_text or rt.turn.text
        return {}
    await shopping.explain_search(_rt(config), state["message"], state.get("slots") or {}, state.get("data"))
    return {}


async def sh_compare(state: AgentState, config) -> AgentState:
    await shopping.compare(_rt(config), state["route"], state["message"])
    return {}


async def sh_knowledge(state: AgentState, config) -> AgentState:
    rt = _rt(config)
    route = state["route"]
    live = None
    if route.slots.get("about_selected"):
        card = await product_context.selected_card(rt)
        if card:
            rt.turn.products = [card]
            live = {"selected_product_from_dsports": card}
    await shopping.knowledge(rt, route, state["message"], live=live)
    return {}


async def sh_recommend(state: AgentState, config) -> AgentState:
    await recommend.recommend_for_chat(_rt(config), state["route"])
    return {}


async def sh_kit(state: AgentState, config) -> AgentState:
    await recommend.build_kit_for_chat(_rt(config), state["route"])
    return {}


async def sh_detail(state: AgentState, config) -> AgentState:
    await product_context.detail(_rt(config), state["route"], state["message"])
    return {}


async def sh_reviews(state: AgentState, config) -> AgentState:
    await product_context.reviews(_rt(config), state["route"])
    return {}


def sh_dispatch(state: AgentState) -> str:
    intent = state["route"].intent
    return {Intent.COMPARE: "compare", Intent.PRODUCT_ADVICE: "compare", Intent.PRODUCT_KNOWLEDGE: "knowledge",
            Intent.PRODUCT_DETAIL: "detail", Intent.PRODUCT_REVIEWS: "reviews", Intent.RECOMMEND: "recommend", Intent.KIT_BUILD: "kit"}.get(intent, "search")


def shopping_graph():
    g = StateGraph(AgentState)
    g.add_node("search", sh_search)
    g.add_node("explain", sh_explain)
    g.add_node("compare", sh_compare)
    g.add_node("knowledge", sh_knowledge)
    g.add_node("detail", sh_detail)
    g.add_node("reviews", sh_reviews)
    g.add_node("recommend", sh_recommend)
    g.add_node("kit", sh_kit)
    g.add_conditional_edges(START, sh_dispatch, {"search": "search", "compare": "compare", "knowledge": "knowledge",
                                                  "detail": "detail", "reviews": "reviews", "recommend": "recommend", "kit": "kit"})
    g.add_edge("search", "explain")
    for n in ("explain", "compare", "knowledge", "detail", "reviews", "recommend", "kit"):
        g.add_edge(n, END)
    return g.compile()


# ------------------------------------------------------------------------------------------------ orders / returns subgraph

def _simple(fn):
    async def node(state: AgentState, config) -> AgentState:
        await fn(_rt(config), state["route"])
        return {}
    return node


async def or_return_check(state: AgentState, config) -> AgentState:
    await orders.return_check(_rt(config), state["route"], state["message"])
    return {}


async def or_confirm_yes(state: AgentState, config) -> AgentState:
    await orders.confirm_yes(_rt(config))
    return {}


async def or_confirm_no(state: AgentState, config) -> AgentState:
    await orders.confirm_no(_rt(config))
    return {}


_ORDER_NODES = {
    Intent.ORDERS_LIST: "orders_list", Intent.ORDER_DETAIL: "order_detail", Intent.TRACK_ORDER: "track", Intent.PAYMENT_STATUS: "payment",
    Intent.REFUND_STATUS: "refund", Intent.CART_VIEW: "cart", Intent.RETURN_CHECK: "return_check", Intent.RETURN_CREATE: "return_create",
    Intent.REORDER: "reorder", Intent.CONFIRM_YES: "confirm_yes", Intent.CONFIRM_NO: "confirm_no",
}


def orders_graph():
    g = StateGraph(AgentState)
    g.add_node("orders_list", _simple(orders.orders_list))
    g.add_node("order_detail", _simple(orders.order_detail))
    g.add_node("track", _simple(orders.track))
    g.add_node("payment", _simple(orders.payment_status))
    async def refund(state, config):
        await orders.refund_status(_rt(config), state["route"]); return {}
    g.add_node("refund", refund)
    g.add_node("cart", _simple(orders.cart_view))
    g.add_node("return_check", or_return_check)
    g.add_node("return_create", _simple(orders.return_create))
    g.add_node("reorder", _simple(orders.reorder))
    g.add_node("confirm_yes", or_confirm_yes)
    g.add_node("confirm_no", or_confirm_no)
    g.add_conditional_edges(START, lambda s: _ORDER_NODES[s["route"].intent], {n: n for n in _ORDER_NODES.values()})
    for n in _ORDER_NODES.values():
        g.add_edge(n, END)
    return g.compile()


# ------------------------------------------------------------------------------------------------ support subgraph

async def su_policy(state: AgentState, config) -> AgentState:
    await support.support_policy(_rt(config), state["route"], state["message"])
    return {}


async def su_case(state: AgentState, config) -> AgentState:
    """The old 'raise a complaint / speak to a person' intent: it now starts the real complaint workflow instead of declining."""
    route = state["route"]
    await support_flow.complaint(_rt(config), Route(Intent.COMPLAINT, route.tier, {**route.slots, "complaint": route.slots.get("complaint") or "GENERAL"}), state["message"])
    return {}


def _support_node(fn):
    async def node(state: AgentState, config) -> AgentState:
        await fn(_rt(config), state["route"], state["message"])
        return {}
    return node


async def su_refund_why(state: AgentState, config) -> AgentState:
    await support.refund_why(_rt(config), state["route"], state["message"])
    return {}


def support_graph():
    g = StateGraph(AgentState)
    g.add_node("policy", su_policy)
    g.add_node("refund_why", su_refund_why)
    g.add_node("case", su_case)
    flows = {"complaint": support_flow.complaint, "case_create": support_flow.case_create, "case_status": support_flow.case_status,
             "case_message": support_flow.case_message, "case_escalate": support_flow.case_escalate, "case_reopen": support_flow.case_reopen}
    for name, fn in flows.items():
        g.add_node(name, _support_node(fn))
    by_intent = {Intent.REFUND_WHY: "refund_why", Intent.SUPPORT_CASE: "case", Intent.COMPLAINT: "complaint", Intent.CASE_CREATE: "case_create",
                 Intent.CASE_STATUS: "case_status", Intent.CASE_MESSAGE: "case_message", Intent.CASE_ESCALATE: "case_escalate", Intent.CASE_REOPEN: "case_reopen"}
    g.add_conditional_edges(START, lambda s: by_intent.get(s["route"].intent, "policy"),
                            {n: n for n in ("policy", "refund_why", "case", *flows)})
    for n in ("policy", "refund_why", "case", *flows):
        g.add_edge(n, END)
    return g.compile()


# ------------------------------------------------------------------------------------------------ assembly

def build_customer_agent():
    g = StateGraph(AgentState)
    g.add_node("guard_in", guard_in)
    g.add_node("route", route_node)
    g.add_node("shopping", shopping_graph())
    g.add_node("orders_returns", orders_graph())
    g.add_node("support", support_graph())
    g.add_node("smalltalk", smalltalk)
    g.add_node("guard_out", guard_out)
    g.add_edge(START, "guard_in")
    g.add_conditional_edges("guard_in", after_guard, {"route": "route", "guard_out": "guard_out"})
    g.add_conditional_edges("route", dispatch, {"shopping": "shopping", "orders_returns": "orders_returns", "support": "support",
                                                "smalltalk": "smalltalk", "guard_out": "guard_out"})
    for n in ("shopping", "orders_returns", "support", "smalltalk"):
        g.add_edge(n, "guard_out")
    g.add_edge("guard_out", END)
    return g.compile()
