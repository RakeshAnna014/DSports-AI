CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    payment_reference VARCHAR(50) NOT NULL,
    order_id        UUID NOT NULL,
    user_id         UUID NOT NULL,
    amount          DECIMAL(12,2) NOT NULL,
    currency        VARCHAR(3) NOT NULL,
    payment_method  VARCHAR(20),
    payment_provider VARCHAR(20),
    transaction_id  VARCHAR(100),
    gateway_reference VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    failure_reason  TEXT,
    paid_at         TIMESTAMP WITH TIME ZONE,
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_payment_status CHECK (status IN (
        'CREATED', 'PENDING', 'AUTHORIZED', 'SUCCESS', 'FAILED', 'CANCELLED', 'REFUNDED'
    ))
);

CREATE UNIQUE INDEX idx_payments_payment_reference ON payments(payment_reference);
CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_user_id ON payments(user_id);
CREATE INDEX idx_payments_status ON payments(status);

CREATE TABLE payment_audit (
    id              UUID PRIMARY KEY,
    payment_id      UUID NOT NULL REFERENCES payments(id),
    event_type      VARCHAR(50) NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20) NOT NULL,
    details         TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_audit_payment_id ON payment_audit(payment_id);
CREATE INDEX idx_payment_audit_created_at ON payment_audit(created_at);
