"""The structured type of a response, decided by the service from what it actually holds, so the UI renders components, not paragraphs.

TEXT, PRODUCT_LIST, PRODUCT_SELECTION, PRODUCT_DETAIL, PRODUCT_COMPARISON, ORDER_LIST, ORDER_TRACKING, RETURN_PRODUCT_SELECTION,
RETURN_OPTIONS, CONFIRMATION, REVIEWS, CART, PAYMENTS, REFUNDS, SUPPORT_CASE, SUPPORT_CASES, SUPPORT_INVESTIGATION, ERROR.
(RECOMMENDATIONS is produced by the recommendations endpoint, not by chat.)
"""
from __future__ import annotations

from app.agent.runtime import Turn
from app.router.intents import Intent


def response_type(t: Turn, intent: str) -> str:
    if t.status in ("error", "ai_unavailable"):
        return "ERROR"
    if t.actions or t.action_result:
        return "CONFIRMATION"
    if t.selection:
        return "RETURN_PRODUCT_SELECTION" if t.selection.get("purpose") == "RETURN" else "PRODUCT_SELECTION"
    if t.recommendations:
        return "RECOMMENDATIONS"
    if t.comparison:
        return "PRODUCT_COMPARISON"
    if t.support:
        return {"INVESTIGATION": "SUPPORT_INVESTIGATION", "ASK_INFO": "SUPPORT_INVESTIGATION", "CASE_LIST": "SUPPORT_CASES"}.get(t.support.get("kind"), "SUPPORT_CASE")
    if t.tracking:
        return "ORDER_TRACKING"
    if t.eligibility:
        return "RETURN_OPTIONS"
    if t.reviews:
        return "REVIEWS"
    if intent == Intent.PRODUCT_DETAIL.value and t.products:
        return "PRODUCT_DETAIL"
    if t.products:
        return "PRODUCT_LIST"
    if t.orders:
        return "ORDER_LIST"
    if t.cart:
        return "CART"
    if t.refunds:
        return "REFUNDS"
    if t.payments:
        return "PAYMENTS"
    if intent == Intent.SUPPORT_CASE.value:
        return "SUPPORT_CASE"
    return "TEXT"
