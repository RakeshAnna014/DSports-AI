"""Connection pool and the AI-owned schema. The store is optional: without it the service still starts and reports it."""
from __future__ import annotations

import logging
from pathlib import Path

import asyncpg

from app.obs.usage import UsageRecord

log = logging.getLogger(__name__)
_SCHEMA = Path(__file__).with_name("schema.sql")
_SCHEMA_RAG = Path(__file__).with_name("schema_rag.sql")
_SCHEMA_INVENTORY = Path(__file__).parent.parent / "inventory" / "schema_inventory.sql"
_SCHEMA_ENGINEERING = Path(__file__).parent.parent / "engineering" / "schema_engineering.sql"
_SCHEMA_SUPPORT_STAFF = Path(__file__).parent.parent / "support" / "schema_support_staff.sql"
_SCHEMA_SOURCING = Path(__file__).parent.parent / "sourcing" / "schema_sourcing.sql"
_SCHEMA_PLANNING = Path(__file__).parent.parent / "planning" / "schema_planning.sql"
_SCHEMA_LOCK_KEY = 7_020_001  # serialises concurrent starts of several replicas


class Store:
    def __init__(self, pool: asyncpg.Pool, embedding_dim: int = 768):
        self._pool = pool
        self._dim = embedding_dim

    @property
    def pool(self) -> asyncpg.Pool:
        return self._pool

    @classmethod
    async def connect(cls, dsn: str, timeout_s: float, embedding_dim: int = 768) -> "Store":
        pool = await asyncpg.create_pool(dsn, min_size=1, max_size=10, timeout=timeout_s, command_timeout=timeout_s)
        store = cls(pool, embedding_dim)
        await store.ensure_schema()
        return store

    async def close(self) -> None:
        await self._pool.close()

    async def ensure_schema(self) -> None:
        """Idempotent (CREATE ... IF NOT EXISTS). Three small tables do not justify a migration framework yet."""
        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock($1)", _SCHEMA_LOCK_KEY)
            await conn.execute(_SCHEMA.read_text(encoding="utf-8"))
            await conn.execute(_SCHEMA_RAG.read_text(encoding="utf-8").replace("{{DIM}}", str(self._dim)))
            await conn.execute(_SCHEMA_INVENTORY.read_text(encoding="utf-8"))
            await conn.execute(_SCHEMA_ENGINEERING.read_text(encoding="utf-8"))
            await conn.execute(_SCHEMA_SUPPORT_STAFF.read_text(encoding="utf-8"))
            await conn.execute(_SCHEMA_SOURCING.read_text(encoding="utf-8"))
            await conn.execute(_SCHEMA_PLANNING.read_text(encoding="utf-8"))

    async def ping(self) -> None:
        async with self._pool.acquire() as conn:
            await conn.fetchval("SELECT 1")

    async def check_embedding_config(self, model: str, dim: int) -> str:
        """Record the embedding model/dimension used to build vectors and detect a later change.

        Returns "initialized" (first time), "ok" (unchanged) or "reindex_required" (model or dimension changed). The
        stored value is NOT overwritten on a change: it is updated only when a re-index completes (a later phase).
        """
        wanted = f"{model}|{dim}"
        async with self._pool.acquire() as conn, conn.transaction():
            await conn.execute("SELECT pg_advisory_xact_lock($1)", _SCHEMA_LOCK_KEY)
            current = await conn.fetchval("SELECT value FROM ai_meta WHERE key = 'embedding_config'")
            if current is None:
                await conn.execute("INSERT INTO ai_meta (key, value) VALUES ('embedding_config', $1)", wanted)
                return "initialized"
            return "ok" if current == wanted else "reindex_required"

    async def write(self, record: UsageRecord) -> None:
        """UsageSink implementation."""
        async with self._pool.acquire() as conn:
            await conn.execute(
                """INSERT INTO usage (ts, correlation_id, request_id, conversation_id, customer_hash, operation, model,
                                      tier, intent, tokens_in, tokens_out, latency_ms, cost_estimate, cost_currency,
                                      cost_is_estimate, outcome)
                   VALUES ($1,$2,$3,$4::uuid,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)""",
                record.ts, record.correlation_id, record.request_id, record.conversation_id, record.customer_hash,
                record.operation, record.model, record.tier, record.intent, record.tokens_in, record.tokens_out,
                record.latency_ms, record.cost_estimate, record.cost_currency, record.cost_is_estimate, record.outcome,
            )
