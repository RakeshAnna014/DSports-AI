"""Re-embed everything after the embedding model (or its dimension) changed.

Vectors from different models are not comparable, so a changed model/dimension means ALL stored vectors are stale. The
reindex: (1) resizes the vector columns if the dimension changed, (2) re-embeds every product and every chunk from the text
already stored (documents are not re-extracted), (3) only then records the new model in ai_meta. Until it finishes the stored
config keeps saying 'reindex_required'.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

from app.rag.chunking import Chunk
from app.rag.embedder import Embedder, to_pgvector
from app.rag.ingest import embedding_input, content_hash
from app.rag.products import ProductIndexer
from app.store.db import Store

log = logging.getLogger(__name__)


@dataclass
class ReindexSummary:
    previous: str | None
    current: str
    products: int
    chunks: int
    resized: bool


async def reindex(store: Store, indexer: ProductIndexer, embedder: Embedder, *, force: bool = False) -> ReindexSummary:
    pool = store.pool
    wanted = f"{embedder.model}|{embedder.dim}"
    previous = await pool.fetchval("SELECT value FROM ai_meta WHERE key='embedding_config'")
    if previous == wanted and not force:
        return ReindexSummary(previous, wanted, 0, 0, False)
    resized = previous is not None and previous.split("|")[-1] != str(embedder.dim)
    if resized:
        async with pool.acquire() as conn, conn.transaction():
            for index in ("ix_product_index_embedding", "ix_chunks_embedding"):
                await conn.execute(f"DROP INDEX IF EXISTS {index}")
            for table in ("product_index", "chunks"):
                await conn.execute(f"ALTER TABLE {table} ALTER COLUMN embedding TYPE vector({embedder.dim}) USING NULL")
            await conn.execute("CREATE INDEX ix_product_index_embedding ON product_index USING hnsw (embedding vector_cosine_ops)")
            await conn.execute("CREATE INDEX ix_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops)")
    summary = await indexer.index_all(force=True)

    rows = await pool.fetch("""SELECT c.id, c.content, c.section, d.title FROM chunks c JOIN documents d ON d.id = c.document_id ORDER BY c.id""")
    inputs = [embedding_input(r["title"], Chunk(0, r["content"], r["section"], 0)) for r in rows]
    vectors = await embedder.embed_documents(inputs) if inputs else []
    async with pool.acquire() as conn, conn.transaction():
        for r, text, vec in zip(rows, inputs, vectors):
            await conn.execute("UPDATE chunks SET embedding=$2::vector, content_hash=$3 WHERE id=$1", r["id"], to_pgvector(vec),
                               content_hash(text, embedder.model))
        await conn.execute("UPDATE documents SET embedding_model=$1", embedder.model)
        await conn.execute("""INSERT INTO ai_meta (key, value) VALUES ('embedding_config', $1)
                              ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value, updated_at=now()""", wanted)
    log.info("reindex complete", extra={"previous": previous, "current": wanted, "products": summary.total, "chunks": len(rows)})
    return ReindexSummary(previous, wanted, summary.total, len(rows), resized)
