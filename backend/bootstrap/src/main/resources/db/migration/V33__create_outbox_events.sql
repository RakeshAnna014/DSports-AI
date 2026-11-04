-- Transactional outbox: follow-up work recorded in the same transaction as the business change that needs it,
-- delivered by OutboxDispatcher with retries. (event_type, aggregate_id) makes enqueuing idempotent.
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(50)  NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status_valid CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT uq_outbox_type_aggregate UNIQUE (event_type, aggregate_id)
);

CREATE INDEX ix_outbox_due ON outbox_events (next_attempt_at) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX ix_outbox_failed ON outbox_events (created_at) WHERE status = 'FAILED';
