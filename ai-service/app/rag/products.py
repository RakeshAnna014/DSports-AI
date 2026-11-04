"""The derived product index (searchable text + embedding per product) and its reindex logic.

The index holds descriptive facts only. It NEVER holds price, stock, or anything about a customer or an order: those are
fetched live from DSports at answer time. It is rebuildable from the catalog at any moment.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass

from app.rag.embedder import Embedder, content_hash, to_pgvector
from app.shop.catalog import Catalog, ProductFacts
from app.store.db import Store

log = logging.getLogger(__name__)
_CAMEL = re.compile(r"(?<=[a-z])(?=[A-Z])")


def readable_key(key: str) -> str:
    return _CAMEL.sub(" ", key).replace("_", " ").lower()


def compose_search_text(p: ProductFacts) -> str:
    """Natural-language document describing the product (what the keyword and embedding indexes see)."""
    parts = [p.name + "."]
    audience = " ".join(x for x in (p.brand, p.category, f"for {p.sport}" if p.sport else None) if x)
    if audience:
        parts.append(audience + ".")
    if p.description:
        parts.append(p.description.strip())
    attrs = "; ".join(f"{readable_key(k)}: {v.replace('_', ' ')}" for k, v in sorted(p.attributes.items()))
    if attrs:
        parts.append(f"Attributes - {attrs}.")
    if p.weight_g is not None:
        parts.append(f"Weight: {p.weight_g:g} g.")
    return " ".join(parts)


@dataclass
class IndexSummary:
    total: int
    indexed: int
    unchanged: int
    removed: int
    model: str


class ProductIndexer:
    def __init__(self, store: Store, catalog: Catalog, embedder: Embedder):
        self._store, self._catalog, self._embedder = store, catalog, embedder

    async def index_all(self, *, force: bool = False) -> IndexSummary:
        rows = await self._catalog.list_products()
        facts = await self._catalog.details([r["id"] for r in rows])
        pool = self._store.pool
        existing = {r["product_id"]: r for r in await pool.fetch(
            "SELECT product_id::text AS product_id, content_hash, embedding IS NOT NULL AS has_embedding FROM product_index")}
        todo: list[tuple[ProductFacts, str, str]] = []
        for pid, p in facts.items():
            text = compose_search_text(p)
            h = content_hash(text, p.brand or "", p.category or "", p.sport or "", str(p.weight_g), self._embedder.model)
            old = existing.get(pid)
            if force or old is None or old["content_hash"] != h or not old["has_embedding"]:
                todo.append((p, text, h))
        vectors = await self._embedder.embed_documents([t for _, t, _ in todo]) if todo else []
        async with pool.acquire() as conn, conn.transaction():
            for (p, text, h), vec in zip(todo, vectors):
                await conn.execute(
                    """INSERT INTO product_index (product_id, sku, name, brand, category, sport, attributes, weight_g, search_text,
                                                  content_hash, embedding, embedding_model, indexed_at)
                       VALUES ($1::uuid,$2,$3,$4,$5,$6,$7::jsonb,$8,$9,$10,$11::vector,$12, now())
                       ON CONFLICT (product_id) DO UPDATE SET sku=EXCLUDED.sku, name=EXCLUDED.name, brand=EXCLUDED.brand,
                         category=EXCLUDED.category, sport=EXCLUDED.sport, attributes=EXCLUDED.attributes, weight_g=EXCLUDED.weight_g,
                         search_text=EXCLUDED.search_text, content_hash=EXCLUDED.content_hash, embedding=EXCLUDED.embedding,
                         embedding_model=EXCLUDED.embedding_model, indexed_at=now()""",
                    p.product_id, p.sku, p.name, p.brand, p.category, p.sport, _json(p.attributes), p.weight_g, text, h,
                    to_pgvector(vec), self._embedder.model)
            stale = [pid for pid in existing if pid not in facts]
            if stale:
                await conn.execute("DELETE FROM product_index WHERE product_id = ANY($1::uuid[])", stale)
        summary = IndexSummary(len(facts), len(todo), len(facts) - len(todo), len(existing.keys() - facts.keys()), self._embedder.model)
        log.info("product index updated", extra=summary.__dict__)
        return summary

    async def sku_to_id(self) -> dict[str, str]:
        rows = await self._store.pool.fetch("SELECT sku, product_id::text AS id FROM product_index WHERE sku IS NOT NULL")
        return {r["sku"]: r["id"] for r in rows}


def _json(value: dict) -> str:
    import json
    return json.dumps(value)
