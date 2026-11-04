"""Hybrid retrieval over the AI database: PostgreSQL full-text search + pgvector cosine similarity, fused with RRF.

  FTS list      ts_rank_cd over an OR-query of the question's words (so a long natural question still matches)
  vector list   cosine distance to the query embedding (HNSW index)
  fusion        Reciprocal Rank Fusion: score(d) = sum over lists of 1 / (RRF_K + rank_in_list(d))

RRF needs no score calibration between the two very different scales, which is why it is used instead of a weighted sum.
Metadata filters (sport, category, brand, document type, product) are applied INSIDE both queries. Reranking is not done:
it is off by default and only worth enabling if the evaluation shows a measurable gain.
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any

from app.llm.base import LLMError
from app.obs import metrics
from app.rag.embedder import Embedder, to_pgvector
from app.store.db import Store

log = logging.getLogger(__name__)
RRF_K = 60
# Internal document families. Each value is the exact, fixed SQL pattern prefix; nothing a caller supplies is ever interpolated.
INTERNAL_PREFIXES = {"inv_": r"inv\_", "sup_": r"sup\_"}
_WORD = re.compile(r"[A-Za-z0-9]{3,}")


def rrf_fuse(rankings: list[list[str]], k: int = RRF_K) -> dict[str, float]:
    scores: dict[str, float] = {}
    for ranking in rankings:
        for position, item in enumerate(ranking, start=1):
            scores[item] = scores.get(item, 0.0) + 1.0 / (k + position)
    return scores


def or_tsquery(text: str) -> str | None:
    words = list(dict.fromkeys(w.lower() for w in _WORD.findall(text)))[:24]
    return " | ".join(words) if words else None


@dataclass
class Hit:
    id: str
    score: float
    similarity: float | None = None   # cosine similarity (1 = identical); None when only the keyword list found it
    keyword_rank: int | None = None
    vector_rank: int | None = None
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass
class Retrieval:
    hits: list[Hit]
    latency_ms: int
    embedding_ms: int
    candidates: int


class Retriever:
    def __init__(self, store: Store, embedder: Embedder, candidates: int = 20, min_similarity: float = 0.0):
        self._store = store
        self._embedder = embedder
        self._n = candidates
        self._min_sim = min_similarity

    async def _query_vector(self, query: str) -> tuple[str | None, int]:
        """Embed the query. If the embedding model is unavailable, degrade to keyword-only search instead of failing."""
        try:
            vector = to_pgvector(await self._embedder.embed_query(query))
            return vector, self._embedder.last_latency_ms
        except LLMError as exc:
            log.warning("embedding unavailable; keyword-only retrieval", extra={"code": exc.code})
            return None, 0

    # ------------------------------------------------------------------ products
    async def search_products(self, query: str, *, sport: str | None = None, category: str | None = None,
                              brand: str | None = None, limit: int | None = None) -> Retrieval:
        started = time.perf_counter()
        vector, embedding_ms = await self._query_vector(query)
        where, args = ["TRUE"], []
        for column, value in (("sport", sport), ("category", category), ("brand", brand)):
            if value:
                args.append(value)
                where.append(f"lower({column}) = lower(${len(args)})")
        clause = " AND ".join(where)
        base = len(args)
        pool = self._store.pool
        sims = await pool.fetch(
            f"""SELECT product_id::text AS id, name, 1 - (embedding <=> ${base + 1}::vector) AS sim
                FROM product_index WHERE embedding IS NOT NULL AND {clause}
                ORDER BY embedding <=> ${base + 1}::vector LIMIT {self._n}""", *args, vector) if vector else []
        tsq = or_tsquery(query)
        kw = await pool.fetch(
            f"""SELECT product_id::text AS id, name, ts_rank_cd(tsv, to_tsquery('english', ${base + 1})) AS r
                FROM product_index WHERE {clause} AND tsv @@ to_tsquery('english', ${base + 1})
                ORDER BY r DESC LIMIT {self._n}""", *args, tsq) if tsq else []
        hits = self._fuse(sims, kw, limit or self._n)
        if hits:
            rows = await pool.fetch(
                """SELECT product_id::text AS id, sku, name, brand, category, sport, attributes, weight_g
                   FROM product_index WHERE product_id = ANY($1::uuid[])""", [h.id for h in hits])
            by_id = {r["id"]: dict(r) for r in rows}
            for h in hits:
                h.payload = by_id.get(h.id, h.payload)
        result = Retrieval(hits, int((time.perf_counter() - started) * 1000), embedding_ms, len({r["id"] for r in sims} | {r["id"] for r in kw}))
        metrics.RETRIEVAL_LATENCY.labels("products").observe(result.latency_ms / 1000)
        return result

    # ------------------------------------------------------------------ knowledge chunks
    async def search_chunks(self, query: str, *, document_types: list[str] | None = None, sport: str | None = None,
                            category: str | None = None, product_id: str | None = None, limit: int = 5, internal: bool | str = False) -> Retrieval:
        """`internal=False` (customers) NEVER returns business documents; `internal=True` (inventory intelligence) returns ONLY `inv_` documents and
        `internal="sup_"` (support staff) ONLY `sup_` documents. Internal documents carry a document_type prefix (`inv_`, `sup_`); the separation is
        enforced here in SQL, not in a prompt, and the prefix is chosen from a fixed list, never from caller text."""
        started = time.perf_counter()
        vector, embedding_ms = await self._query_vector(query)
        prefix = INTERNAL_PREFIXES["inv_" if internal is True else internal] if internal else None
        where, args = [rf"c.document_type LIKE '{prefix}%'" if prefix else r"c.document_type NOT LIKE 'inv\_%' AND c.document_type NOT LIKE 'sup\_%'"], []
        if document_types:
            args.append(document_types)
            where.append(f"c.document_type = ANY(${len(args)}::text[])")
        # General documents (no sport/category/product) always qualify: policies apply to every product.
        for column, value in (("sport", sport), ("category", category)):
            if value:
                args.append(value)
                where.append(f"(c.{column} IS NULL OR lower(c.{column}) = lower(${len(args)}))")
        if product_id:
            args.append(product_id)
            where.append(f"(c.product_id IS NULL OR c.product_id = ${len(args)}::uuid)")
        clause = " AND ".join(where)
        base = len(args)
        pool = self._store.pool
        sims = await pool.fetch(
            f"""SELECT c.id::text AS id, 1 - (c.embedding <=> ${base + 1}::vector) AS sim
                FROM chunks c WHERE c.embedding IS NOT NULL AND {clause}
                ORDER BY c.embedding <=> ${base + 1}::vector LIMIT {self._n}""", *args, vector) if vector else []
        tsq = or_tsquery(query)
        kw = await pool.fetch(
            f"""SELECT c.id::text AS id, ts_rank_cd(c.tsv, to_tsquery('english', ${base + 1})) AS r
                FROM chunks c WHERE {clause} AND c.tsv @@ to_tsquery('english', ${base + 1})
                ORDER BY r DESC LIMIT {self._n}""", *args, tsq) if tsq else []
        hits = self._fuse(sims, kw, limit)
        if self._min_sim > 0:  # a weak embedding match with no keyword support is noise, not evidence
            hits = [h for h in hits if h.keyword_rank is not None or (h.similarity or 0.0) >= self._min_sim]
        if hits:
            rows = await pool.fetch(
                """SELECT c.id::text AS id, c.content, c.source, c.page, c.document_type, c.section, c.chunk_index,
                          c.product_id::text AS product_id, d.title, d.version, d.ocr_used
                   FROM chunks c JOIN documents d ON d.id = c.document_id WHERE c.id = ANY($1::uuid[])""",
                [h.id for h in hits])
            by_id = {r["id"]: dict(r) for r in rows}
            hits = [h for h in hits if h.id in by_id]
            for h in hits:
                h.payload = by_id[h.id]
        metrics.record_rag(bool(hits))
        result = Retrieval(hits, int((time.perf_counter() - started) * 1000), embedding_ms, len({r["id"] for r in sims} | {r["id"] for r in kw}))
        metrics.RETRIEVAL_LATENCY.labels("chunks").observe(result.latency_ms / 1000)
        return result

    @staticmethod
    def _fuse(sims, kw, limit: int) -> list[Hit]:
        vec_rank = {r["id"]: i for i, r in enumerate(sims, start=1)}
        kw_rank = {r["id"]: i for i, r in enumerate(kw, start=1)}
        similarity = {r["id"]: float(r["sim"]) for r in sims}
        fused = rrf_fuse([[r["id"] for r in sims], [r["id"] for r in kw]])
        names = {r["id"]: r["name"] for r in list(sims) + list(kw) if "name" in r.keys()}
        ordered = sorted(fused, key=lambda i: (-fused[i], vec_rank.get(i, 10**6), i))[:limit]
        return [Hit(id=i, score=round(fused[i], 6), similarity=similarity.get(i), keyword_rank=kw_rank.get(i),
                    vector_rank=vec_rank.get(i), payload={"name": names[i]} if i in names else {}) for i in ordered]
