"""Checkout offer recommendations: the backend's offers, ranked and explained only with evidence that is true of this customer."""
from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.agent.promo_offers import recommend_offers
from app.errors import ApiException
from app.tools.dsports_client import ToolError

CHECKOUT = "00000000-0000-0000-0000-0000000000c1"
BAT = "00000000-0000-0000-0000-0000000000a1"
BALL = "00000000-0000-0000-0000-0000000000a2"


def offer(code: str, *, eligible=True, discount="200.00", scope="ALL", names=None, reason=None, max_discount=None, applied=False, demo=True, subtotal="2000.00") -> dict:
    return {"code": code, "title": code.title(), "description": f"DEMO OFFER (synthetic) {code}", "discountType": "PERCENT", "discountValue": 10,
            "maxDiscountAmount": max_discount, "minOrderAmount": 500, "scope": scope, "scopeNames": names or [], "validUntil": None, "eligible": eligible,
            "discountAmount": discount if eligible else "0.00", "eligibleSubtotal": subtotal, "ineligibleReason": reason, "applied": applied, "demo": demo}


class FakeDSports:
    def __init__(self, checkout: dict | None, offers: list[dict], error: ToolError | None = None):
        self.checkout, self.offers, self.error, self.calls = checkout, offers, error, []

    async def get(self, path: str, *, token: str | None = None, **_: Any):
        self.calls.append((path, token))
        if self.error:
            raise self.error
        return self.checkout if path.endswith(CHECKOUT) else self.offers


class FakeRuntime:
    def __init__(self, dsports: FakeDSports, *, products: dict | None = None, orders: list | None = None, profile: dict | None = None):
        self.principal = SimpleNamespace(token="jwt-of-the-customer")
        self.ctx = SimpleNamespace(dsports=dsports, max_calls=30)
        self.products, self.orders, self.profile = products or {}, orders or [], profile

    async def call(self, name: str, args: dict):
        if name == "get_product":
            p = self.products.get(args["product_id"])
            return SimpleNamespace(ok=bool(p), data={"product": p} if p else None)
        if name == "get_orders":
            return SimpleNamespace(ok=True, data={"orders": self.orders})
        if name == "get_shopper_profile":
            return SimpleNamespace(ok=self.profile is not None, data={"profile": self.profile} if self.profile else None)
        raise AssertionError(name)


def checkout_with(*product_ids: str) -> dict:
    return {"id": CHECKOUT, "items": [{"productId": p, "quantity": 1} for p in product_ids]}


PRODUCTS = {BAT: {"product_id": BAT, "name": "Bat", "sport": "Cricket", "brand": "SG", "category": "Bat", "price": 2000.0, "attributes": {}},
            BALL: {"product_id": BALL, "name": "Football", "sport": "Football", "brand": "Nike", "category": "Ball", "price": 900.0, "attributes": {}}}
CRICKET_ORDER = [{"items": [{"product_id": BAT, "unit_price": 2000.0}]}]
OWNER = {"kind": "OWNER", "personalization_enabled": True, "preferences": {"sports": ["Cricket"], "empty": False}}


@pytest.mark.asyncio
async def test_a_cricket_buyer_with_cricket_in_the_cart_gets_the_cricket_offer_first_with_true_reasons():
    ds = FakeDSports(checkout_with(BAT), [offer("WELCOME10", discount="200.00"), offer("CRICKET15", discount="300.00", scope="SPORT", names=["Cricket"])])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS, orders=CRICKET_ORDER, profile=OWNER), CHECKOUT)
    first = result["offers"][0]
    assert first["code"] == "CRICKET15" and first["recommended"] is True and first["personal"] is True
    assert any("Covers 1 Cricket item(s) in your cart" in r for r in first["reasons"])
    assert any(r.startswith("You have bought Cricket gear before") for r in first["reasons"])
    assert any("Cricket is one of the sports in your profile" in r for r in first["reasons"])
    assert "Saves ₹300 on this cart" in first["reasons"]
    assert result["personalized"] is True and set(result["basedOn"]) == {"your cart", "your order history", "your profile"}


@pytest.mark.asyncio
async def test_with_no_history_and_no_profile_nothing_personal_is_claimed():
    ds = FakeDSports(checkout_with(BAT), [offer("CRICKET15", scope="SPORT", names=["Cricket"])])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS, orders=[], profile=None), CHECKOUT)
    reasons = " ".join(result["offers"][0]["reasons"])
    assert "bought" not in reasons and "profile" not in reasons
    assert result["personalized"] is False and result["basedOn"] == ["your cart"]


@pytest.mark.asyncio
async def test_history_in_another_sport_does_not_justify_a_cricket_offer():
    football_order = [{"items": [{"product_id": BALL, "unit_price": 900.0}]}]
    ds = FakeDSports(checkout_with(BAT), [offer("CRICKET15", scope="SPORT", names=["Cricket"])])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS, orders=football_order, profile=None), CHECKOUT)
    assert not any("bought" in r for r in result["offers"][0]["reasons"])


@pytest.mark.asyncio
async def test_personalisation_switched_off_or_a_family_profile_uses_only_the_cart_and_the_offer():
    ds = FakeDSports(checkout_with(BAT), [offer("CRICKET15", scope="SPORT", names=["Cricket"])])
    off = {"kind": "OWNER", "personalization_enabled": False, "preferences": {"sports": ["Cricket"], "empty": False}}
    child = {"kind": "FAMILY", "personalization_enabled": True, "preferences": {"sports": ["Cricket"], "empty": False}}
    for profile in (off, child):
        result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS, orders=CRICKET_ORDER, profile=profile), CHECKOUT)
        assert result["personalizationOff"] is True and result["personalized"] is False
        assert not any("bought" in r or "profile" in r for r in result["offers"][0]["reasons"])


@pytest.mark.asyncio
async def test_no_eligible_offer_is_said_plainly_and_carries_the_backends_reason_without_inventing_one():
    ds = FakeDSports(checkout_with(BALL), [offer("FLAT200", eligible=False, reason="Add INR 2100.00 more of eligible items to use this offer (minimum INR 3000.00).")])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS), CHECKOUT)
    assert result["personalized"] is False
    assert result["message"].startswith("No offer applies to this cart right now.")
    assert "Add INR 2100.00 more" in result["message"]
    assert result["offers"][0]["eligible"] is False and result["offers"][0]["reasons"] == []
    assert not any(o.get("recommended") for o in result["offers"])


@pytest.mark.asyncio
async def test_with_no_offers_at_all_the_answer_is_honest_and_empty():
    result = await recommend_offers(FakeRuntime(FakeDSports(checkout_with(BAT), [])), CHECKOUT)
    assert result["offers"] == [] and result["message"] == "No offer applies to this cart right now."


@pytest.mark.asyncio
async def test_amounts_are_exactly_the_backends_and_the_cap_is_mentioned_only_when_reached():
    ds = FakeDSports(checkout_with(BAT), [offer("WELCOME10", discount="300.00", max_discount="300.00"), offer("SMALL", discount="50.00", max_discount="300.00")])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS), CHECKOUT)
    by = {o["code"]: o for o in result["offers"]}
    assert by["WELCOME10"]["discountAmount"] == "300.00" and by["SMALL"]["discountAmount"] == "50.00"
    assert any("most the offer gives" in r for r in by["WELCOME10"]["reasons"])
    assert not any("most the offer gives" in r for r in by["SMALL"]["reasons"])


@pytest.mark.asyncio
async def test_the_checkout_of_another_customer_is_reported_as_not_available_and_nothing_leaks():
    for code in ("FORBIDDEN", "NOT_FOUND"):
        ds = FakeDSports(None, [], error=ToolError(code, "secret internal detail", 403))
        with pytest.raises(ApiException) as err:
            await recommend_offers(FakeRuntime(ds), CHECKOUT)
        assert err.value.status == 404 and "secret" not in err.value.message


@pytest.mark.asyncio
async def test_the_customers_own_token_is_forwarded_and_the_disclaimer_labels_demo_offers():
    ds = FakeDSports(checkout_with(BAT), [offer("WELCOME10")])
    result = await recommend_offers(FakeRuntime(ds, products=PRODUCTS), CHECKOUT)
    assert {tok for _, tok in ds.calls} == {"jwt-of-the-customer"}
    assert "synthetic demonstration offers" in result["disclaimer"] and result["offers"][0]["demo"] is True
