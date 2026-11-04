"""Shared helpers for the support workflow tests: a rig with realistic orders, shipments, payments, returns and policy passages."""
from __future__ import annotations

from app.agent.schemas import ChatRequest, ChatResponse, ConfirmRequest
from tests.fakes import BAT_MRF, GLOVES, RUN_SHOES, USER_A, USER_B, chunk
from tests.helpers import Rig, make_rig

POLICY = [
    chunk("p-missing", "missing-delivery-procedure.md", "procedure",
          "If your order is marked delivered but you did not receive it, check with your household and neighbours, then raise a support case. A person reviews the delivery records.",
          title="Missing delivery procedure (sample)"),
    chunk("p-delay", "shipping-policy.md", "policy", "A delayed order can be tracked with its tracking number. If it has not arrived by its estimated date a person can follow it up with the courier.",
          title="Shipping and tracking (sample)"),
    chunk("p-damaged", "damaged-product-procedure.md", "procedure", "If a product arrives damaged, raise a support case and keep the packaging. A delivered order can be returned within the return window.",
          title="Damaged product procedure (sample)"),
    chunk("p-payment", "payment-failure-procedure.md", "procedure", "If your payment was deducted but your order failed, check the payment status. A bank hold from a failed attempt is released by the bank.",
          title="Payment failure procedure (sample)"),
    chunk("p-refund", "refund-policy.md", "policy", "Approval of a return records that the goods are accepted. The refund is a separate step handled by the DSports team and the money reaches the bank afterwards.",
          title="Refund policy (sample)"),
    chunk("p-return", "return-policy.md", "policy", "A product can be returned within the return window after delivery. A rejected return can be reviewed by a person.",
          title="Return policy (sample)"),
]


async def support_rig(**kw) -> Rig:
    rig = await make_rig(**kw)
    rig.retriever.chunks.extend(dict(c) for c in POLICY)
    return rig


async def say(rig: Rig, text: str, *, who="alice", conv: str | None = None, choice: str | None = None) -> ChatResponse:
    principal = rig.alice if who == "alice" else rig.bob
    return await rig.chat.chat(principal, ChatRequest(message=text, conversation_id=conv, choice_id=choice))


async def confirm(rig: Rig, resp: ChatResponse, *, who="alice") -> ChatResponse:
    principal = rig.alice if who == "alice" else rig.bob
    return await rig.chat.confirm(principal, ConfirmRequest(confirmation_token=resp.confirmation_token, conversation_id=resp.conversation_id))


def tools(resp: ChatResponse) -> list[str]:
    return [t["tool"] for t in resp.meta.tools]


__all__ = ["support_rig", "say", "confirm", "tools", "BAT_MRF", "GLOVES", "RUN_SHOES", "USER_A", "USER_B"]
