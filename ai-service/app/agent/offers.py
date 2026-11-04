"""Server-owned choices: what the customer can tap instead of typing.

Whenever the assistant offers options ("Which product would you like to return?", "Return this item"), the SERVICE records what each option
means (an intent and the exact order/product ids it offered) and hands the UI only an opaque `choiceId`. When the customer taps one, the UI
sends that `choiceId`; the service looks it up in the offers it made in its previous response and runs exactly that. Neither the client nor
the model can supply or alter an id, and an unknown or stale `choiceId` does nothing.
"""
from __future__ import annotations

import uuid
from typing import Any

from app.agent.runtime import Runtime
from app.router.intents import Intent


def add_offer(rt: Runtime, intent: Intent, slots: dict[str, Any], label: str) -> str:
    """Register an option for THIS response and return its opaque id."""
    choice_id = uuid.uuid4().hex[:12]
    rt.turn.offers[choice_id] = {"intent": intent.value, "slots": dict(slots), "label": label}
    return choice_id


def add_chip(rt: Runtime, label: str, message: str | None = None) -> None:
    """A follow-up the customer can tap: it is sent as an ordinary message (understood like any typed message)."""
    rt.turn.suggestions.append({"label": label, "message": message or label, "choice_id": None})


def add_action(rt: Runtime, label: str, intent: Intent, slots: dict[str, Any]) -> None:
    """A follow-up that runs a specific, already-resolved step (for example "Return this item")."""
    rt.turn.suggestions.append({"label": label, "message": label, "choice_id": add_offer(rt, intent, slots, label)})


def resolve(conv_slots: dict[str, Any], choice_id: str | None) -> dict[str, Any] | None:
    """The offer made in the previous response, or None (unknown, expired or someone else's: never trusted)."""
    if not choice_id:
        return None
    offers = conv_slots.get("offers") or {}
    return offers.get(choice_id)
