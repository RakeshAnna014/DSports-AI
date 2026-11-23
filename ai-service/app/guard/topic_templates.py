"""Every reply the topic gate gives is a fixed template with suggestion chips: no model call, no data lookup, nothing interpolated from the message.

A chip is either a message chip (tapping it sends ``message`` as the customer's next message) or a link chip (``href`` is an app route).
The texts never repeat what the customer wrote, so an attack or an off-topic request is not echoed back.
"""
from __future__ import annotations

from dataclasses import dataclass

from app.guard.topic import Audience, Topic


@dataclass(frozen=True)
class Chip:
    label: str
    message: str | None = None
    href: str | None = None

    def as_suggestion(self) -> dict[str, str | None]:
        return {"label": self.label, "message": self.message, "choice_id": None, "href": self.href}


@dataclass(frozen=True)
class Template:
    key: str
    text: str
    chips: tuple[Chip, ...]


SHOPPER_CHIPS = (Chip("Find a cricket bat", message="Show me cricket bats"), Chip("Track my order", message="Where is my latest order?"),
                 Chip("Courts and venues", href="/venues"), Chip("My matches", href="/matches"))
ADMIN_CHIPS = (Chip("Inventory Intelligence", href="/inventory-ai"), Chip("Sales planning", href="/sales-planning"), Chip("Find a cricket bat", message="Show me cricket bats"))

OFF_TOPIC = Template("off_topic",
                     "I can only help with DSports: shopping for sports gear, your orders, returns and refunds, court and venue bookings, and your cricket matches and rules. "
                     "What would you like to do?", SHOPPER_CHIPS)
BUSINESS_REFUSED = Template("business_refused",
                            "Sales, stock numbers, forecasts, suppliers and other internal business information aren't available through this assistant. "
                            "I can help with products, your own orders, bookings and your cricket matches.", SHOPPER_CHIPS)
BUSINESS_POINTER = Template("business_pointer",
                            "Business questions like sales, stock and forecasts are answered in the staff AI workspace, which uses the verified data and its own access controls. "
                            "I can't answer them from this chat.", ADMIN_CHIPS)
UNSURE = Template("unsure",
                  "I couldn't tell what you're asking. Could you rephrase it? For example, ask about a product, your order, a court booking or your cricket matches.",
                  SHOPPER_CHIPS)
BOOKING_POINTER = Template("booking_pointer",
                           "I can't search or book courts from this chat yet. The venues page shows what is available and lets you book, and your bookings are under My bookings.",
                           (Chip("Courts and venues", href="/venues"), Chip("My bookings", href="/bookings")))
CRICKET_POINTER = Template("cricket_pointer",
                           "I can't read out your matches or stats from this chat yet. Your matches, scorecards and career stats are on their own pages.",
                           (Chip("My matches", href="/matches"), Chip("Live matches", href="/live"), Chip("My teams", href="/teams")))
COACHING_SOON = Template("coaching_soon",
                         "Coaching tips are coming soon. For now I can explain cricket rules and terms such as LBW, a free hit or net run rate, and help you find gear.",
                         (Chip("What is LBW?", message="What is LBW?"), Chip("Find a cricket bat", message="Show me cricket bats"), Chip("My matches", href="/matches")))

# Appended after the answer to the in-scope part of a mixed message. It does not say what the declined part was.
MIXED_DECLINE = "I can't help with the other part of your message: I only answer DSports shopping, booking and cricket questions."


def refusal(topic: Topic, audience: Audience) -> Template:
    """The fixed reply for a message the policy does not allow. Only BUSINESS (for a non-admin) and OFF_TOPIC are ever refused."""
    return BUSINESS_REFUSED if topic is Topic.BUSINESS else OFF_TOPIC
