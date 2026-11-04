-- AI-owned schema (database dsports_ai). Derived AI state only: no product, price, stock, order or payment data is
-- copied here, and customers are referenced by an HMAC hash, never by their DSports user id.
-- Product embeddings / RAG tables are intentionally NOT part of this foundation.

CREATE TABLE IF NOT EXISTS ai_meta (
    key        TEXT PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS conversations (
    id            UUID PRIMARY KEY,
    customer_hash TEXT        NOT NULL,
    summary       TEXT,
    state         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours')
);
CREATE INDEX IF NOT EXISTS ix_conversations_owner ON conversations (customer_hash, updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_conversations_expiry ON conversations (expires_at);

CREATE TABLE IF NOT EXISTS audit (
    id              BIGSERIAL PRIMARY KEY,
    ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
    customer_hash   TEXT        NOT NULL,
    conversation_id UUID,
    correlation_id  TEXT,
    action          TEXT        NOT NULL,
    tool            TEXT,
    args_hash       TEXT,
    outcome         TEXT        NOT NULL,
    detail          JSONB
);
CREATE INDEX IF NOT EXISTS ix_audit_customer_ts ON audit (customer_hash, ts DESC);
CREATE INDEX IF NOT EXISTS ix_audit_correlation ON audit (correlation_id);

CREATE TABLE IF NOT EXISTS usage (
    id               BIGSERIAL PRIMARY KEY,
    ts               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    correlation_id   TEXT,
    request_id       TEXT,
    conversation_id  UUID,
    customer_hash    TEXT,
    operation        TEXT          NOT NULL,
    model            TEXT          NOT NULL,
    tier             TEXT,
    intent           TEXT,
    tokens_in        INTEGER,
    tokens_out       INTEGER,
    latency_ms       INTEGER       NOT NULL,
    cost_estimate    NUMERIC(14, 8),
    cost_currency    TEXT          NOT NULL DEFAULT 'USD',
    cost_is_estimate BOOLEAN       NOT NULL DEFAULT TRUE,
    outcome          TEXT          NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_usage_ts ON usage (ts DESC);
CREATE INDEX IF NOT EXISTS ix_usage_model_ts ON usage (model, ts DESC);

INSERT INTO ai_meta (key, value) VALUES ('schema_version', '1') ON CONFLICT (key) DO NOTHING;

-- Single-use confirmation for state-changing assistant actions. The token itself is never stored; only its nonce.
CREATE TABLE IF NOT EXISTS pending_actions (
    nonce           TEXT PRIMARY KEY,
    customer_hash   TEXT        NOT NULL,
    conversation_id UUID,
    tool            TEXT        NOT NULL,
    args            JSONB       NOT NULL,
    args_hash       TEXT        NOT NULL,
    summary         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING | CONSUMED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    outcome         TEXT
);
CREATE INDEX IF NOT EXISTS ix_pending_customer ON pending_actions (customer_hash, created_at DESC);
