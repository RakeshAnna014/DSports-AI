-- Derived retrieval data (rebuildable). {{DIM}} is the configured embedding dimension.
-- NO price, stock, order, payment or customer data is stored here: those are always fetched live from DSports.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS product_index (
    product_id      UUID PRIMARY KEY,
    sku             TEXT,
    name            TEXT        NOT NULL,
    brand           TEXT,
    category        TEXT,
    sport           TEXT,
    attributes      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    weight_g        DOUBLE PRECISION,
    search_text     TEXT        NOT NULL,
    content_hash    TEXT        NOT NULL,
    embedding       vector({{DIM}}),
    embedding_model TEXT,
    indexed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', search_text)) STORED
);
CREATE INDEX IF NOT EXISTS ix_product_index_tsv ON product_index USING GIN (tsv);
CREATE INDEX IF NOT EXISTS ix_product_index_embedding ON product_index USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS ix_product_index_filters ON product_index (sport, category);

CREATE TABLE IF NOT EXISTS documents (
    id              UUID PRIMARY KEY,
    source          TEXT        NOT NULL UNIQUE,   -- stable logical name, e.g. return-policy.md
    document_type   TEXT        NOT NULL,          -- guide | care | warranty | policy | faq | spec
    title           TEXT,
    version         TEXT        NOT NULL DEFAULT '1',
    language        TEXT        NOT NULL DEFAULT 'en',
    pages           INTEGER     NOT NULL DEFAULT 1,
    ocr_used        BOOLEAN     NOT NULL DEFAULT FALSE,
    content_hash    TEXT        NOT NULL,
    embedding_model TEXT        NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chunks (
    id              UUID PRIMARY KEY,
    document_id     UUID        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    product_id      UUID,
    category        TEXT,
    sport           TEXT,
    brand           TEXT,
    document_type   TEXT        NOT NULL,
    source          TEXT        NOT NULL,
    version         TEXT        NOT NULL,
    language        TEXT        NOT NULL,
    page            INTEGER     NOT NULL,
    chunk_index     INTEGER     NOT NULL,
    section         TEXT,
    content         TEXT        NOT NULL,
    content_hash    TEXT        NOT NULL,
    embedding       vector({{DIM}}),
    tsv             tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);
CREATE INDEX IF NOT EXISTS ix_chunks_tsv ON chunks USING GIN (tsv);
CREATE INDEX IF NOT EXISTS ix_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS ix_chunks_doc ON chunks (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS ix_chunks_filters ON chunks (document_type, sport, category);
