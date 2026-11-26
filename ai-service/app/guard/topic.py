"""Which topics the assistant answers, for whom. Pure code: the decision is made on the verified principal, never by a prompt.

Topics (what the message is about):
  SHOP      products, gear advice, stock availability of a product, the customer's own orders, returns, refunds
  BOOKING   venues, courts, slots, the customer's own bookings
  CRICKET   the customer's own matches, scores, stats, teams; rules and definitions of the game; coaching and technique
  BUSINESS  sales, revenue, margins, warehouse stock numbers, forecasts, suppliers, other customers' data, internal operations
  OFF_TOPIC everything else: general knowledge, coding, homework, news, results and trivia

Policy: guests and customers get SHOP, BOOKING and CRICKET; admins get those plus BUSINESS; OFF_TOPIC is refused for everyone.
"""
from __future__ import annotations

from enum import StrEnum
from typing import Protocol


class Topic(StrEnum):
    SHOP = "SHOP"
    BOOKING = "BOOKING"
    CRICKET = "CRICKET"
    BUSINESS = "BUSINESS"
    OFF_TOPIC = "OFF_TOPIC"


class Audience(StrEnum):
    """Low cardinality on purpose: it is also a metric label."""
    GUEST = "guest"
    CUSTOMER = "customer"
    ADMIN = "admin"


class _HasAdmin(Protocol):
    @property
    def is_admin(self) -> bool: ...


_PUBLIC = frozenset({Topic.SHOP, Topic.BOOKING, Topic.CRICKET})

ALLOWED: dict[Audience, frozenset[Topic]] = {
    Audience.GUEST: _PUBLIC,
    Audience.CUSTOMER: _PUBLIC,
    Audience.ADMIN: _PUBLIC | {Topic.BUSINESS},
}


def audience_of(principal: _HasAdmin | None) -> Audience:
    """No verified principal is a guest; only a verified ADMIN role is an admin. Anything else signed in is a customer."""
    if principal is None:
        return Audience.GUEST
    return Audience.ADMIN if principal.is_admin else Audience.CUSTOMER


def is_allowed(audience: Audience, topic: Topic) -> bool:
    return topic in ALLOWED[audience]
