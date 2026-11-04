-- Captured payments can be refunded partially; refunded_amount only grows after the gateway confirmed a refund.
ALTER TABLE payments ADD COLUMN refunded_amount DECIMAL(12, 2) NOT NULL DEFAULT 0;
ALTER TABLE payments ADD CONSTRAINT ck_payment_refunded_within_amount
    CHECK (refunded_amount >= 0 AND refunded_amount <= amount);
ALTER TABLE payments ADD CONSTRAINT ck_payment_amount_positive CHECK (amount > 0);

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payment_status;
ALTER TABLE payments ADD CONSTRAINT chk_payment_status CHECK (status IN (
    'CREATED', 'PENDING', 'PROCESSING', 'AUTHORIZED', 'SUCCESS', 'PARTIALLY_REFUNDED', 'FAILED', 'CANCELLED', 'REFUNDED'
));

-- At most one live payment per order (open or captured); FAILED/CANCELLED attempts allow a retry.
CREATE UNIQUE INDEX uq_payments_order_open ON payments (order_id)
    WHERE status IN ('CREATED', 'PENDING', 'PROCESSING', 'AUTHORIZED', 'SUCCESS', 'PARTIALLY_REFUNDED', 'REFUNDED');

CREATE TABLE refunds (
    id                UUID PRIMARY KEY,
    payment_id        UUID           NOT NULL REFERENCES payments (id),
    order_id          UUID           NOT NULL,
    customer_id       UUID           NOT NULL,
    amount            DECIMAL(12, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    reason            VARCHAR(500),
    requested_by      UUID           NOT NULL,
    idempotency_key   VARCHAR(100)   NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    gateway_reference VARCHAR(100),
    failure_reason    TEXT,
    attempts          INTEGER        NOT NULL DEFAULT 0,
    version           INTEGER        NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT ck_refund_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_refund_status_valid CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED', 'REJECTED')),
    CONSTRAINT uq_refund_payment_idempotency_key UNIQUE (payment_id, idempotency_key)
);

CREATE INDEX ix_refunds_payment_id ON refunds (payment_id);
CREATE INDEX ix_refunds_status ON refunds (status);
