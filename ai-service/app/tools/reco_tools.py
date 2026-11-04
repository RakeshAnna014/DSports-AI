"""READ tools for budget- and context-aware recommendations. Both only read Java-owned data through the customer's own token."""
from __future__ import annotations

import uuid

from pydantic import Field

from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext


class ProfileArgs(StrictArgs):
    profile_id: uuid.UUID | None = Field(None, description="A profile of this account (own or family). Omit for the active profile")


class CoPurchaseArgs(StrictArgs):
    product_id: uuid.UUID
    limit: int = Field(10, ge=1, le=20)


def _clean_profile(p: dict) -> dict:
    """Whitelist what the pipeline may use. Names of family members are kept out of ranking inputs; only the display name is returned for the UI."""
    prefs = p.get("preferences") or {}
    return {
        "profile_id": p["id"], "kind": p["kind"], "display_name": p.get("displayName"), "age_band": p.get("ageBand"),
        "personalization_enabled": bool(p.get("personalizationEnabled", True)),
        "preferences": {
            "sports": prefs.get("sports") or [], "brands": prefs.get("favoriteBrands") or [], "categories": prefs.get("favoriteCategories") or [],
            "teams": prefs.get("favoriteTeams") or [], "usage_level": prefs.get("usageLevel"),
            "price_min": prefs.get("priceMin"), "price_max": prefs.get("priceMax"), "sizes": prefs.get("sizes") or {},
            "attributes": prefs.get("attributes") or {}, "empty": bool(prefs.get("empty", True)),
        },
    }


async def get_shopper_profile(ctx: ToolContext, a: ProfileArgs):
    path = f"/api/customers/me/profiles/{a.profile_id}" if a.profile_id else "/api/customers/me/profiles/active"
    data = await ctx.dsports.get(path, token=ctx.token, allow_404=True)
    if not data:
        raise ToolError("NOT_FOUND", "That profile is not available")   # another account's id is indistinguishable from a missing one
    return {"profile": _clean_profile(data)}


async def get_co_purchases(ctx: ToolContext, a: CoPurchaseArgs):
    data = await ctx.dsports.get("/api/v1/insights/co-purchases", token=ctx.token, params={"productId": str(a.product_id), "limit": a.limit})
    return {"product_id": str(a.product_id), "minimum_orders": data.get("minimumOrders"),
            "pairs": [{"product_id": c["productId"], "orders": c["orders"]} for c in data.get("coPurchased", [])]}


def tools() -> list[Tool]:
    return [
        Tool("get_shopper_profile", "The signed-in customer's active (or a named) shopper profile with optional preferences.", Kind.READ,
             ProfileArgs, get_shopper_profile),
        Tool("get_co_purchases", "Products bought together with a product, with the number of distinct orders as evidence.", Kind.READ,
             CoPurchaseArgs, get_co_purchases),
    ]
