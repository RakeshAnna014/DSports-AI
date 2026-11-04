"""Product discovery pipeline (everything except the optional LLM explanation):

   slots -> hybrid retrieval (keyword + vector, metadata filtered) -> LIVE price + stock (DSports)
         -> budget / availability filters -> deterministic ranking -> top N (+ live details and ratings)

The LLM is not involved anywhere in this class. Price, stock and the choice/order of products are decided by data and
fixed rules; the model only gets to explain the result afterwards.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any

from pydantic import BaseModel, ConfigDict

from app.rag.retrieval import RRF_K, Retriever
from app.shop.catalog import Catalog, ProductFacts
from app.shop.ranking import Preferences, Ranked, rank

log = logging.getLogger(__name__)
MAX_CANDIDATES = 20
RATING_LOOKUPS = 8
SCORE_CEILING = 2.0 / (RRF_K + 1)   # best possible fused score: rank 1 in both the keyword and the vector list
NEAR_MISS = 1.25   # an over-budget alternative may exceed the budget by at most 25%


class SearchSlots(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str | None = None
    sport: str | None = None
    category: str | None = None
    brand: str | None = None
    skill_level: str | None = None
    max_price: float | None = None
    min_price: float | None = None
    weight_pref: str | None = None
    age_group: str | None = None
    limit: int = 5
    in_stock_only: bool = True

    def preferences(self) -> Preferences:
        return Preferences(sport=self.sport, category=self.category, skill_level=self.skill_level,
                           max_price=self.max_price, min_price=self.min_price, weight_pref=self.weight_pref, age_group=self.age_group)


@dataclass
class SearchOutcome:
    ranked: list[Ranked]
    considered: int
    relaxed: list[str] = field(default_factory=list)
    over_budget: bool = False          # nothing fit the budget: these are the closest alternatives, flagged as such
    excluded_out_of_stock: int = 0
    retrieval: dict[str, Any] = field(default_factory=dict)


def query_text(slots: SearchSlots) -> str:
    if slots.query:
        return slots.query
    words = [slots.skill_level, "lightweight" if slots.weight_pref == "light" else "heavy" if slots.weight_pref == "heavy" else None,
             slots.sport, slots.category, slots.brand]
    return " ".join(w for w in words if w) or "sports equipment"


def _from_index(payload: dict[str, Any], product_id: str) -> ProductFacts:
    import json
    attrs = payload.get("attributes") or {}
    if isinstance(attrs, str):
        attrs = json.loads(attrs)
    return ProductFacts(product_id=product_id, sku=payload.get("sku"), name=payload.get("name") or "", brand=payload.get("brand"),
                        category=payload.get("category"), sport=payload.get("sport"),
                        attributes={str(k): str(v) for k, v in attrs.items()}, weight_g=payload.get("weight_g"))


class ProductSearch:
    def __init__(self, retriever: Retriever, catalog: Catalog):
        self._retriever = retriever
        self._catalog = catalog

    async def search(self, slots: SearchSlots, token: str | None = None) -> SearchOutcome:
        text = query_text(slots)
        relaxed: list[str] = []
        sport, category, brand = slots.sport, slots.category, slots.brand
        retrieval = await self._retriever.search_products(text, sport=sport, category=category, brand=brand, limit=MAX_CANDIDATES)
        # Over-constrained (e.g. an unknown category word): widen step by step and say so, never silently.
        for dropped in ("brand", "category", "sport"):
            if retrieval.hits:
                break
            if dropped == "brand" and brand:
                brand = None
            elif dropped == "category" and category:
                category = None
            elif dropped == "sport" and sport:
                sport = None
            else:
                continue
            relaxed.append(dropped)
            retrieval = await self._retriever.search_products(text, sport=sport, category=category, brand=brand, limit=MAX_CANDIDATES)

        meta = {"latency_ms": retrieval.latency_ms, "embedding_ms": retrieval.embedding_ms, "k": len(retrieval.hits),
                "names": [h.payload.get("name") for h in retrieval.hits],
                "hits": [{"product_id": h.id, "score": h.score, "similarity": h.similarity, "keyword_rank": h.keyword_rank,
                          "vector_rank": h.vector_rank} for h in retrieval.hits]}
        if not retrieval.hits:
            return SearchOutcome([], 0, relaxed, retrieval=meta)

        ids = [h.id for h in retrieval.hits]
        raw = {h.id: h.score for h in retrieval.hits}
        # LIVE price and stock for every candidate (2 batch calls). No live price = not purchasable now: dropped.
        prices, stock = await asyncio.gather(self._catalog.prices(ids, token), self._catalog.stock(ids, token))
        base: dict[str, ProductFacts] = {}
        for h in retrieval.hits:
            if h.id in prices:
                base[h.id] = _from_index(h.payload, h.id).model_copy(update={"price": prices[h.id], "stock": stock.get(h.id)})
        considered = len(base)

        pool = list(base.values())
        out_of_stock = 0
        if slots.in_stock_only:
            before = len(pool)
            pool = [p for p in pool if p.stock is not None and p.stock.in_stock]
            out_of_stock = before - len(pool)
        in_budget = [p for p in pool if self._fits(p, slots)]
        over_budget = False
        if not in_budget and pool and (slots.max_price is not None or slots.min_price is not None):
            # Nothing fits: offer the closest options, clearly flagged, rather than an empty answer or a silent override.
            # Nothing fits: offer near misses only (within NEAR_MISS of the limit), clearly flagged. Far-off products are not
            # "nearest options", they are noise; an empty list plus an honest message is the better answer then.
            over_budget = True
            if slots.max_price is not None:
                near = [p for p in pool if p.price.selling_price <= slots.max_price * NEAR_MISS]
                in_budget = sorted(near, key=lambda p: p.price.selling_price)[:3]
            else:
                near = [p for p in pool if p.price.selling_price >= (slots.min_price or 0) / NEAR_MISS]
                in_budget = sorted(near, key=lambda p: -p.price.selling_price)[:3]

        prefs = slots.preferences()
        if over_budget:
            prefs = prefs.model_copy(update={"max_price": None, "min_price": None})
        pre = rank([(p, raw[p.product_id]) for p in in_budget], prefs, score_ceiling=SCORE_CEILING)
        shortlist = pre[: max(slots.limit, RATING_LOOKUPS)]
        ratings = await self._catalog.ratings([r.product.product_id for r in shortlist], token)
        enriched = [(r.product.model_copy(update={"rating": ratings.get(r.product.product_id)}), raw[r.product.product_id]) for r in shortlist]
        final = rank(enriched, prefs, score_ceiling=SCORE_CEILING)[: slots.limit]

        # Present live details (current description/attributes/weight) for what we actually show.
        details = await self._catalog.details([r.product.product_id for r in final], token)
        shown = [Ranked(r.product.model_copy(update={
            k: v for k, v in (details[r.product.product_id].model_dump(include={"name", "brand", "category", "sport", "description",
                                                                              "attributes", "weight_g", "dimensions", "image_url", "sku"})).items()
        }) if r.product.product_id in details else r.product, r.score, r.factors, r.semantic) for r in final]
        return SearchOutcome(shown, considered, relaxed, over_budget, out_of_stock, meta)

    @staticmethod
    def _fits(p: ProductFacts, slots: SearchSlots) -> bool:
        if p.price is None:
            return False
        price = p.price.selling_price
        return (slots.max_price is None or price <= slots.max_price) and (slots.min_price is None or price >= slots.min_price)
