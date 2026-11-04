"""Intents, slots and the routing decision shared by Tier 0 (rules) and Tier 1 (cheap model)."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any


class Intent(StrEnum):
    PRODUCT_SEARCH = "PRODUCT_SEARCH"
    REFINE_SEARCH = "REFINE_SEARCH"
    PRODUCT_ADVICE = "PRODUCT_ADVICE"
    COMPARE = "COMPARE"
    PRODUCT_KNOWLEDGE = "PRODUCT_KNOWLEDGE"
    PRODUCT_DETAIL = "PRODUCT_DETAIL"
    PRODUCT_REVIEWS = "PRODUCT_REVIEWS"
    RECOMMEND = "RECOMMEND"
    KIT_BUILD = "KIT_BUILD"
    SUPPORT_POLICY = "SUPPORT_POLICY"
    SUPPORT_CASE = "SUPPORT_CASE"
    COMPLAINT = "COMPLAINT"            # a problem to investigate (delivery, payment, refund, return, product)
    CASE_CREATE = "CASE_CREATE"        # raise a case from what was just found
    CASE_STATUS = "CASE_STATUS"        # what happened to my complaint / show my cases
    CASE_MESSAGE = "CASE_MESSAGE"      # add a message to my case
    CASE_ESCALATE = "CASE_ESCALATE"    # ask for a person to review my case
    CASE_REOPEN = "CASE_REOPEN"
    ORDERS_LIST = "ORDERS_LIST"
    ORDER_DETAIL = "ORDER_DETAIL"
    TRACK_ORDER = "TRACK_ORDER"
    PAYMENT_STATUS = "PAYMENT_STATUS"
    REFUND_STATUS = "REFUND_STATUS"
    REFUND_WHY = "REFUND_WHY"
    RETURN_CHECK = "RETURN_CHECK"
    RETURN_CREATE = "RETURN_CREATE"
    REORDER = "REORDER"
    CART_VIEW = "CART_VIEW"
    CONFIRM_YES = "CONFIRM_YES"
    CONFIRM_NO = "CONFIRM_NO"
    SMALLTALK = "SMALLTALK"
    OUT_OF_SCOPE = "OUT_OF_SCOPE"
    BLOCKED = "BLOCKED"


# Intents a guest (not signed in) may use: public product data and public knowledge only.
GUEST_INTENTS = {Intent.PRODUCT_SEARCH, Intent.REFINE_SEARCH, Intent.PRODUCT_ADVICE, Intent.COMPARE, Intent.PRODUCT_KNOWLEDGE,
                 Intent.PRODUCT_DETAIL, Intent.PRODUCT_REVIEWS, Intent.RECOMMEND, Intent.KIT_BUILD, Intent.SUPPORT_POLICY, Intent.SMALLTALK, Intent.OUT_OF_SCOPE, Intent.BLOCKED}

SHOPPING = {Intent.PRODUCT_SEARCH, Intent.REFINE_SEARCH, Intent.PRODUCT_ADVICE, Intent.COMPARE, Intent.PRODUCT_KNOWLEDGE,
            Intent.PRODUCT_DETAIL, Intent.PRODUCT_REVIEWS, Intent.RECOMMEND, Intent.KIT_BUILD}
ORDERS = {Intent.ORDERS_LIST, Intent.ORDER_DETAIL, Intent.TRACK_ORDER, Intent.PAYMENT_STATUS, Intent.REFUND_STATUS,
          Intent.RETURN_CHECK, Intent.RETURN_CREATE, Intent.REORDER, Intent.CART_VIEW}
SUPPORT = {Intent.SUPPORT_POLICY, Intent.SUPPORT_CASE, Intent.REFUND_WHY, Intent.COMPLAINT, Intent.CASE_CREATE, Intent.CASE_STATUS, Intent.CASE_MESSAGE,
           Intent.CASE_ESCALATE, Intent.CASE_REOPEN}
CONFIRM = {Intent.CONFIRM_YES, Intent.CONFIRM_NO}


@dataclass
class Route:
    intent: Intent
    tier: int                      # 0 rules (no LLM), 1 cheap-model classification
    slots: dict[str, Any] = field(default_factory=dict)
    source: str = "rules"          # rules | model | cache | fallback
    tokens_in: int = 0
    tokens_out: int = 0
