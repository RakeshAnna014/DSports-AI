"""READ tools about products and product knowledge. They work for guests too (public data); a token is forwarded if present."""
from __future__ import annotations

import uuid
from typing import Literal

from pydantic import Field

from app.guard.untrusted import sanitize_untrusted
from app.obs import metrics
from app.shop.cards import product_card
from app.shop.search import SearchSlots
from app.tools.dsports_client import ToolError
from app.tools.registry import Kind, StrictArgs, Tool, ToolContext

SkillLevel = Literal["beginner", "intermediate", "advanced"]
WeightPref = Literal["light", "heavy"]
AgeGroup = Literal["junior", "adult"]
DocType = Literal["guide", "care", "warranty", "policy", "faq", "spec", "procedure"]


class SearchProductsArgs(StrictArgs):
    query: str | None = Field(None, max_length=200, description="Free-text description of what the customer wants")
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=40, description="e.g. Bat, Shoes, Ball, Gloves, Jersey")
    brand: str | None = Field(None, max_length=40)
    skill_level: SkillLevel | None = None
    max_price: float | None = Field(None, ge=0, le=10_000_000, description="Budget ceiling in INR")
    min_price: float | None = Field(None, ge=0, le=10_000_000)
    weight_pref: WeightPref | None = None
    age_group: AgeGroup | None = None
    limit: int = Field(5, ge=1, le=10)
    in_stock_only: bool = True


class ProductIdArgs(StrictArgs):
    product_id: uuid.UUID


class ReviewsArgs(StrictArgs):
    product_id: uuid.UUID
    limit: int = Field(5, ge=1, le=10)


class KnowledgeArgs(StrictArgs):
    question: str = Field(min_length=3, max_length=300)
    product_id: uuid.UUID | None = None
    document_types: list[DocType] | None = None
    sport: str | None = Field(None, max_length=40)
    category: str | None = Field(None, max_length=40)
    limit: int | None = Field(None, ge=1, le=8)


class NoArgs(StrictArgs):
    pass


async def search_products(ctx: ToolContext, a: SearchProductsArgs):
    outcome = await ctx.services.search.search(SearchSlots(**a.model_dump()), ctx.token)
    return {
        "products": [product_card(r.product, r, over_budget=outcome.over_budget) for r in outcome.ranked],
        "considered": outcome.considered, "relaxed_filters": outcome.relaxed, "over_budget": outcome.over_budget,
        "excluded_out_of_stock": outcome.excluded_out_of_stock, "retrieval": outcome.retrieval,
    }


async def get_product(ctx: ToolContext, a: ProductIdArgs):
    pid = str(a.product_id)
    live = await ctx.services.catalog.live([pid], ctx.token, with_ratings=True)
    if pid not in live:
        raise ToolError("NOT_FOUND", "That product is not available")
    return {"product": product_card(live[pid])}


async def get_product_reviews(ctx: ToolContext, a: ReviewsArgs):
    pid = str(a.product_id)
    d = ctx.dsports
    summary = await d.get(f"/api/v1/reviews/product/{pid}/summary", token=ctx.token, allow_404=True)
    rows = await d.get(f"/api/v1/reviews/product/{pid}", token=ctx.token, allow_404=True) or []
    reviews = []
    for r in rows[: a.limit]:
        # Whitelist: the reviewer's identity is never copied (the API once leaked userId; this keeps us safe regardless).
        title, _ = sanitize_untrusted(r.get("title") or "", 120)
        comment, _ = sanitize_untrusted(r.get("comment") or "", 300)
        reviews.append({"rating": r.get("rating"), "title": title, "comment": comment,
                        "verified_purchase": bool(r.get("verifiedPurchase")), "created_at": r.get("createdAt")})
    return {"product_id": pid,
            "average_rating": (summary or {}).get("averageRating"), "total_reviews": (summary or {}).get("totalReviews", 0),
            "reviews": reviews}


async def get_product_knowledge(ctx: ToolContext, a: KnowledgeArgs):
    retriever = ctx.services.retriever
    if retriever is None:
        raise ToolError("KNOWLEDGE_UNAVAILABLE", "The knowledge base is not available right now")
    k = a.limit or ctx.settings.top_k
    key = (a.question.strip().lower(), tuple(a.document_types or ()), str(a.product_id or ""), (a.sport or "").lower(), (a.category or "").lower(), k)
    cached = ctx.services.knowledge_cache.get(key)  # public knowledge only: safe to share between customers
    if cached is not None:
        return cached
    r = await retriever.search_chunks(a.question, document_types=list(a.document_types) if a.document_types else None,
                                      sport=a.sport, category=a.category, product_id=str(a.product_id) if a.product_id else None, limit=k)
    chunks, flagged = [], 0
    for h in r.hits:
        text, removed = sanitize_untrusted(h.payload["content"], 1800)  # documents are data, never instructions
        if removed:
            flagged += 1
            metrics.GUARD_BLOCKS.labels("retrieval", ",".join(removed)).inc()
        chunks.append({"chunk_id": h.id, "source": h.payload["source"], "title": h.payload.get("title"), "page": h.payload["page"],
                       "document_type": h.payload["document_type"], "section": h.payload.get("section"), "text": text,
                       "similarity": round(h.similarity, 4) if h.similarity is not None else None,
                       "keyword_rank": h.keyword_rank, "vector_rank": h.vector_rank, "flagged_injection": bool(removed)})
    result = {"found": bool(chunks), "chunks": chunks, "flagged_chunks": flagged,
              "retrieval": {"k": len(chunks), "latency_ms": r.latency_ms, "embedding_ms": r.embedding_ms, "candidates": r.candidates}}
    ctx.services.knowledge_cache.set(key, result)
    return result


def tools() -> list[Tool]:
    return [
        Tool("search_products", "Find products for a customer's need (hybrid search + live price/stock + deterministic ranking).",
             Kind.READ, SearchProductsArgs, search_products, requires_auth=False),
        Tool("get_product", "Get one product's facts with its live price, stock and rating.", Kind.READ, ProductIdArgs,
             get_product, requires_auth=False),
        Tool("get_product_reviews", "Approved customer reviews of a product (reviewer identity is never included).", Kind.READ,
             ReviewsArgs, get_product_reviews, requires_auth=False),
        Tool("get_product_knowledge", "Search the DSports knowledge base (guides, care, warranty, policies, FAQ) and return cited passages.",
             Kind.READ, KnowledgeArgs, get_product_knowledge, requires_auth=False),
    ]
