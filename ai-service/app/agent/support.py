"""Support subgraph nodes: policy / FAQ / warranty / care questions answered from cited sources, and refund questions that
combine the customer's LIVE refund data with the refund policy. There is no case/ticket creation: DSports has no such API."""
from __future__ import annotations

import re

from app.agent import orders, shopping
from app.agent.runtime import Runtime
from app.router.intents import Route


def policy_types(question: str) -> list[str] | None:
    low = question.lower()
    if re.search(r"warrant|guarantee", low):
        return ["warranty"]
    if re.search(r"return|refund|shipping|delivery|cancel|exchange|track|payment", low):
        return ["policy", "faq"]
    return None


async def support_policy(rt: Runtime, route: Route, message: str) -> None:
    await shopping.knowledge(rt, route, message, document_types=policy_types(route.slots.get("question") or message))


async def refund_why(rt: Runtime, route: Route, message: str) -> None:
    data = await orders.refund_status(rt, route)
    live_text = rt.turn.fallback_text
    live = {"customer_refunds_from_dsports": data} if data else None
    await shopping.knowledge(rt, Route(route.intent, route.tier, {"question": "How do refunds work, what do the refund statuses mean, and how long can it take?"}),
                             message, document_types=["policy"], live=live, limit=3)
    has_refunds = any(g.get("refunds") for g in rt.turn.refunds)
    # Only when the customer has no refund of their own: next to real refund statuses a policy list of statuses would be confusing.
    policy_passage = rt.turn.fallback_text if rt.turn.sources and not has_refunds else ""
    # The customer's own refund data leads; if the model's explanation is unavailable or fails verification, the cited policy
    # passage still answers "how long does it take" instead of leaving the customer with only "nothing found".
    rt.turn.fallback_text = f"{live_text}\n\n{policy_passage}" if policy_passage else live_text
    if rt.turn.message_source != "ai":
        rt.turn.text = rt.turn.fallback_text


async def support_case(rt: Runtime) -> None:
    """DSports has no ticketing or live-agent API, so the assistant says so instead of pretending to open a case."""
    rt.turn.text = rt.turn.fallback_text = (
        "I can't create a support ticket or connect you to a person from here, because DSports does not offer that through the assistant yet. "
        "What I can do: check your orders, tracking, payments and refunds, check whether something can be returned and request a return "
        "for you (with your confirmation), and answer questions about shipping, returns, refunds and warranty.")
