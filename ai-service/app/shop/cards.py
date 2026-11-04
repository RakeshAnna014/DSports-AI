"""The structured, customer-visible shape of a product. Built ONLY from tool data (live price/stock, catalogue facts)."""
from __future__ import annotations

from typing import Any

from app.shop.catalog import ProductFacts
from app.shop.ranking import Ranked

MAX_DESCRIPTION = 220


def product_card(p: ProductFacts, ranked: Ranked | None = None, *, over_budget: bool = False) -> dict[str, Any]:
    card: dict[str, Any] = {
        "product_id": p.product_id, "name": p.name, "brand": p.brand, "category": p.category, "sport": p.sport,
        "description": (p.description[:MAX_DESCRIPTION].rstrip() + "...") if p.description and len(p.description) > MAX_DESCRIPTION else p.description,
        "attributes": dict(sorted(p.attributes.items())), "weight_g": p.weight_g, "dimensions": p.dimensions, "image_url": p.image_url,
    }
    if p.price:
        card["price"] = p.price.selling_price
        card["mrp"] = p.price.mrp
        card["currency"] = p.price.currency
        if p.price.mrp and p.price.mrp > p.price.selling_price:
            card["you_save"] = round(p.price.mrp - p.price.selling_price, 2)
    if p.stock:
        card["in_stock"] = p.stock.in_stock
        card["stock_status"] = p.stock.status
        card["available_quantity"] = p.stock.available_quantity
    if p.rating:
        card["rating"] = {"average": p.rating.average, "count": p.rating.count}
    if ranked is not None:
        card["rank_score"] = ranked.score
        card["match_factors"] = ranked.factors
    if over_budget:
        card["over_budget"] = True
    return card
