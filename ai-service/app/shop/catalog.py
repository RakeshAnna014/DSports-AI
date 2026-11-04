"""Live product facts from the DSports public catalog APIs.

Only public reference data (names, attributes, descriptions) is ever cached. Price and stock are fetched live on every
use: they are the Java services' to decide, never ours and never the model's.
"""
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel

from app.tools.dsports_client import DSportsClient, ToolError

_BATCH = 50  # the DSports batch endpoints accept at most 50 ids


class Price(BaseModel):
    mrp: float | None = None
    selling_price: float
    currency: str = "INR"


class Stock(BaseModel):
    available_quantity: int
    status: str

    @property
    def in_stock(self) -> bool:
        return self.available_quantity > 0 and self.status != "OUT_OF_STOCK"


class Rating(BaseModel):
    average: float
    count: int


class ProductFacts(BaseModel):
    """Everything the assistant may state about a product. Absent values stay None and are never inferred."""
    product_id: str
    sku: str | None = None
    name: str
    brand: str | None = None
    category: str | None = None
    sport: str | None = None
    description: str | None = None
    attributes: dict[str, str] = {}
    weight_g: float | None = None
    dimensions: str | None = None
    price: Price | None = None
    stock: Stock | None = None
    rating: Rating | None = None
    image_url: str | None = None


@dataclass
class Taxonomy:
    sports: dict[str, str] = field(default_factory=dict)
    categories: dict[str, str] = field(default_factory=dict)
    brands: dict[str, str] = field(default_factory=dict)
    loaded_at: float = 0.0


def weight_in_grams(value: Any, unit: str | None) -> float | None:
    if value is None:
        return None
    unit = (unit or "").lower()
    if unit in ("kg", "kilogram", "kilograms"):
        return round(float(value) * 1000, 1)
    if unit in ("g", "gram", "grams"):
        return float(value)
    return None  # unknown unit: do not guess


class Catalog:
    def __init__(self, dsports: DSportsClient, taxonomy_ttl_s: int = 3600):
        self._d = dsports
        self._ttl = taxonomy_ttl_s
        self._taxonomy = Taxonomy()
        self._lock = asyncio.Lock()
        self._sem = asyncio.Semaphore(8)

    async def taxonomy(self) -> Taxonomy:
        if self._taxonomy.sports and time.time() - self._taxonomy.loaded_at < self._ttl:
            return self._taxonomy
        async with self._lock:
            if self._taxonomy.sports and time.time() - self._taxonomy.loaded_at < self._ttl:
                return self._taxonomy
            sports, categories, brands = await asyncio.gather(
                self._d.get("/api/catalog/sports"), self._d.get("/api/catalog/categories"), self._d.get("/api/catalog/brands"))
            self._taxonomy = Taxonomy({s["id"]: s["name"] for s in sports}, {c["id"]: c["name"] for c in categories},
                                      {b["id"]: b["name"] for b in brands}, time.time())
            return self._taxonomy

    async def list_products(self) -> list[dict]:
        """All ACTIVE products (summary rows). Used by the indexer, which has no customer token (public endpoint)."""
        out: list[dict] = []
        page = 0
        while True:
            rows = await self._d.get("/api/catalog/products", params={"size": 50, "page": page})
            if not rows:
                break
            out.extend(rows)
            if len(rows) < 50:
                break
            page += 1
        return out

    async def detail(self, product_id: str, token: str | None = None) -> ProductFacts | None:
        async with self._sem:
            raw, attrs = await asyncio.gather(
                self._d.get(f"/api/catalog/products/{product_id}", token=token, allow_404=True),
                self._d.get(f"/api/catalog/products/{product_id}/attributes", token=token, allow_404=True))
        if raw is None:
            return None
        tax = await self.taxonomy()
        dims = None
        if raw.get("length") and raw.get("width") and raw.get("height"):
            dims = f"{raw['length']} x {raw['width']} x {raw['height']} {raw.get('dimensionUnit') or ''}".strip()
        images = raw.get("images") or []
        primary = next((i for i in images if i.get("primary")), images[0] if images else None)
        return ProductFacts(
            product_id=raw["id"], sku=raw.get("sku"), name=raw["name"], brand=tax.brands.get(raw.get("brandId")),
            category=tax.categories.get(raw.get("categoryId")), sport=tax.sports.get(raw.get("sportId")),
            description=raw.get("description"), attributes={str(k): str(v) for k, v in ((attrs or {}).get("attributes") or {}).items()},
            weight_g=weight_in_grams(raw.get("weight"), raw.get("weightUnit")), dimensions=dims,
            image_url=primary.get("url") if primary else None)

    async def details(self, ids: list[str], token: str | None = None) -> dict[str, ProductFacts]:
        found = await asyncio.gather(*(self.detail(i, token) for i in ids))
        return {f.product_id: f for f in found if f is not None}

    async def prices(self, ids: list[str], token: str | None = None) -> dict[str, Price]:
        out: dict[str, Price] = {}
        for i in range(0, len(ids), _BATCH):
            chunk = ids[i:i + _BATCH]
            rows = await self._d.get("/api/prices", token=token, params={"productIds": ",".join(chunk)})
            for r in rows or []:
                out[r["productId"]] = Price(mrp=r.get("mrp"), selling_price=r["sellingPrice"], currency=r.get("currency", "INR"))
        return out

    async def stock(self, ids: list[str], token: str | None = None) -> dict[str, Stock]:
        out: dict[str, Stock] = {}
        for i in range(0, len(ids), _BATCH):
            chunk = ids[i:i + _BATCH]
            rows = await self._d.get("/api/inventory", token=token, params={"productIds": ",".join(chunk)})
            for r in rows or []:
                out[r["productId"]] = Stock(available_quantity=r["availableQuantity"], status=r["status"])
        return out

    async def ratings(self, ids: list[str], token: str | None = None) -> dict[str, Rating]:
        async def one(pid: str):
            async with self._sem:
                try:
                    s = await self._d.get(f"/api/v1/reviews/product/{pid}/summary", token=token, allow_404=True)
                except ToolError:
                    return pid, None
            return pid, (Rating(average=s["averageRating"], count=s["totalReviews"]) if s and s.get("totalReviews") else None)
        return {pid: r for pid, r in await asyncio.gather(*(one(i) for i in ids)) if r is not None}

    async def live(self, ids: list[str], token: str | None = None, *, with_ratings: bool = False) -> dict[str, ProductFacts]:
        """Details + LIVE price + LIVE stock (+ ratings) for the given products. A product with no live price is not purchasable
        right now (inactive or unpriced) and is omitted: the assistant never presents a product it cannot price."""
        ids = list(dict.fromkeys(ids))
        details, prices, stock = await asyncio.gather(self.details(ids, token), self.prices(ids, token), self.stock(ids, token))
        ratings = await self.ratings(list(details), token) if with_ratings else {}
        out: dict[str, ProductFacts] = {}
        for pid, facts in details.items():
            if pid not in prices:
                continue
            out[pid] = facts.model_copy(update={"price": prices[pid], "stock": stock.get(pid), "rating": ratings.get(pid)})
        return out
