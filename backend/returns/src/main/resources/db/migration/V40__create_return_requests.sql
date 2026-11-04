-- Return requests: a customer asks to send back units of one product from a delivered order.
-- Eligibility (delivered, within window, remaining quantity) is enforced by ReturnPolicy; the constraints below are the
-- database's last line of defence.
CREATE TABLE return_requests (
    id              UUID PRIMARY KEY,
    order_id        UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    product_id      UUID         NOT NULL,
    product_name    VARCHAR(255),
    quantity        INTEGER      NOT NULL,
    reason          VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    decision_note   VARCHAR(500),
    version         INTEGER      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_return_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_return_status_valid CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT uq_return_user_idempotency_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX ix_return_requests_order_product ON return_requests (order_id, product_id);
CREATE INDEX ix_return_requests_user_created ON return_requests (user_id, created_at DESC);
CREATE INDEX ix_return_requests_status ON return_requests (status);
