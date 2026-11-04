"""Document/query embedding with batching, a safe query cache and dimension validation."""
from __future__ import annotations

import hashlib
import re

from app.cache import TTLCache
from app.llm.base import EmbeddingClient, LLMError

_BATCH = 16
_WS = re.compile(r"\s+")


def to_pgvector(vector: list[float]) -> str:
    return "[" + ",".join(f"{x:.7g}" for x in vector) + "]"


def content_hash(*parts: str) -> str:
    h = hashlib.sha256()
    for p in parts:
        h.update(p.encode("utf-8")); h.update(b"\x1f")
    return h.hexdigest()


class Embedder:
    def __init__(self, client: EmbeddingClient, model: str, dim: int, cache_ttl_s: int = 3600):
        self._c = client
        self.model = model
        self.dim = dim
        self._cache: TTLCache[list[float]] = TTLCache(cache_ttl_s, maxsize=4096)
        self.last_latency_ms = 0

    def _check(self, dimension: int) -> None:
        if dimension != self.dim:
            raise LLMError("EMBEDDING_DIMENSION_MISMATCH",
                           f"Model returned {dimension} dimensions but AI_EMBEDDING_DIM is {self.dim}; re-index required")

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        out: list[list[float]] = []
        for i in range(0, len(texts), _BATCH):
            result = await self._c.embed(texts[i:i + _BATCH], task_type="RETRIEVAL_DOCUMENT")
            self._check(result.dimension)
            out.extend(result.vectors)
        return out

    async def embed_query(self, text: str) -> list[float]:
        """Cached by normalised text. Queries are not customer-specific vectors, so sharing the cache is safe."""
        key = (self.model, _WS.sub(" ", text.strip().lower()))
        cached = self._cache.get(key)
        if cached is not None:
            self.last_latency_ms = 0
            return cached
        result = await self._c.embed([text], task_type="RETRIEVAL_QUERY")
        self._check(result.dimension)
        self.last_latency_ms = result.latency_ms
        self._cache.set(key, result.vectors[0])
        return result.vectors[0]
