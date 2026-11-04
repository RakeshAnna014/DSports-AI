"""Personalised offer recommendations for the checkout page.

The commerce backend owns promotions: it decides which offers are valid for this cart and what each is worth (`GET /api/v1/checkout/{id}/promotions`).
This module only RANKS those offers for the signed-in customer and explains why, using evidence that exists in the customer's own data:

* the cart (which items an offer actually covers),
* the customer's order history (sports they bought before) and
* the active shopper profile's sports (only when personalisation is switched on).

Nothing here invents an amount, a code, a preference or a purchase. When no offer applies it says so (and passes on the backend's reason, for
example "add INR 200 more"). No model call is made: the ranking is a fixed rule and every sentence is a template over verified values.
"""
from __future__ import annotations

import logging
from collections import Counter
from typing import Any

from app.agent.recommend import _history
from app.errors import ApiException
from app.tools.dsports_client import ToolError

log = logging.getLogger(__name__)

MAX_CART_LOOKUPS = 10
DEMO_NOTE = "Offers marked DEMO are synthetic demonstration offers, not real promotions."


def _inr(v: float | int | str) -> str:
    return f"₹{float(v):,.2f}".replace(".00", "")


def _norm(names: list[str]) -> set[str]:
    return {n.strip().lower() for n in names if n and n.strip()}


async def _checkout_and_offers(rt, checkout_id: str) -> tuple[dict, list[dict]]:
    token = rt.principal.token
    try:
        checkout = await rt.ctx.dsports.get(f"/api/v1/checkout/{checkout_id}", token=token)
        offers = await rt.ctx.dsports.get(f"/api/v1/checkout/{checkout_id}/promotions", token=token)
    except ToolError as e:
        # The backend is the authority: another customer's checkout is a 403/404 there and is reported as "not found" here.
        status = 404 if e.code in ("NOT_FOUND", "FORBIDDEN", "CHECKOUT_NOT_FOUND", "CHECKOUT_NOT_OWNED_BY_CUSTOMER") else 502 if e.code.startswith("UPSTREAM") else 400
        raise ApiException(status, "CHECKOUT_NOT_AVAILABLE" if status == 404 else e.code, "That checkout is not available." if status == 404 else e.message) from None
    return checkout or {}, offers or []


async def _cart_sports(rt, checkout: dict) -> Counter:
    """Sports of the items in the cart, read from the catalogue (the checkout only carries product ids)."""
    sports: Counter = Counter()
    for item in (checkout.get("items") or [])[:MAX_CART_LOOKUPS]:
        res = await rt.call("get_product", {"product_id": item["productId"]})
        if res.ok and res.data["product"].get("sport"):
            sports[res.data["product"]["sport"]] += int(item.get("quantity") or 1)
    return sports


async def _profile(rt) -> dict | None:
    res = await rt.call("get_shopper_profile", {})
    return res.data["profile"] if res.ok else None


async def recommend_offers(rt, checkout_id: str) -> dict[str, Any]:
    """Offers for this checkout, best first, each with the reasons that are true of this customer and cart."""
    checkout, offers = await _checkout_and_offers(rt, checkout_id)
    profile = await _profile(rt)
    personalisation_on = profile is None or (profile.get("kind") == "OWNER" and profile.get("personalization_enabled", True))
    history = await _history(rt) if personalisation_on else {}
    cart_sports = await _cart_sports(rt, checkout)
    profile_sports = _norm(profile["preferences"]["sports"]) if profile and personalisation_on and not profile["preferences"].get("empty", True) else set()
    bought_sports = {s: n for s, n in (history.get("sports") or Counter()).items()}

    ranked: list[dict[str, Any]] = []
    based_on: set[str] = {"your cart"}
    for o in offers:
        reasons: list[str] = []
        personal = 0
        scope = _norm(o.get("scopeNames") or [])
        if o["eligible"]:
            if o["scope"] == "ALL":
                reasons.append("Applies to everything in your cart")
            else:
                covered = [s for s in cart_sports if s.lower() in scope]
                if covered:
                    reasons.append(f"Covers {sum(cart_sports[s] for s in covered)} {', '.join(covered)} item(s) in your cart "
                                   f"({_inr(o['eligibleSubtotal'])} of eligible items)")
            reasons.append(f"Saves {_inr(o['discountAmount'])} on this cart")
            if o.get("maxDiscountAmount") and float(o["discountAmount"]) >= float(o["maxDiscountAmount"]) - 0.005:
                reasons.append(f"This is the most the offer gives ({_inr(o['maxDiscountAmount'])})")
            hit = [s for s in bought_sports if s.lower() in scope]
            if hit:
                personal += 1
                based_on.add("your order history")
                reasons.append("You have bought " + ", ".join(hit) + f" gear before ({sum(bought_sports[s] for s in hit)} of your recent purchases)")
            hit = sorted(s for s in profile_sports if s in scope)
            if hit:
                personal += 1
                based_on.add("your profile")
                reasons.append(f"{', '.join(h.title() for h in hit)} is one of the sports in your profile")
        ranked.append({
            "code": o["code"], "title": o["title"], "description": o["description"], "eligible": o["eligible"], "applied": o.get("applied", False),
            "discountAmount": o["discountAmount"], "eligibleSubtotal": o.get("eligibleSubtotal"), "ineligibleReason": o.get("ineligibleReason"),
            "scope": o["scope"], "scopeNames": o.get("scopeNames") or [], "validUntil": o.get("validUntil"), "demo": bool(o.get("demo")),
            "reasons": reasons, "personal": personal > 0, "_rank": (0 if o["eligible"] else 1, -personal, -float(o["discountAmount"]), o["code"]),
        })
    ranked.sort(key=lambda r: r["_rank"])
    for r in ranked:
        r.pop("_rank")
    eligible = [r for r in ranked if r["eligible"]]
    if eligible:
        message = None
        best = eligible[0]
        best["recommended"] = True
    else:
        message = "No offer applies to this cart right now." + (" " + next((r["ineligibleReason"] for r in ranked if r["ineligibleReason"]), "") if ranked else "")
    return {
        "checkoutId": checkout_id,
        "personalized": any(r["personal"] for r in eligible),
        "basedOn": sorted(based_on),
        "personalizationOff": not personalisation_on,
        "message": message.strip() if message else None,
        "offers": ranked,
        "disclaimer": DEMO_NOTE + " The discount shown is calculated by the DSports backend and confirmed again when you place the order.",
    }
