-- Cart lifecycle: ACTIVE -> CHECKED_OUT (order placed) -> COMPLETED (payment succeeded); ACTIVE -> ABANDONED.
-- V18 predates Cart.complete() and omitted COMPLETED, so post-payment cart completion violated the constraint.
ALTER TABLE carts DROP CONSTRAINT ck_cart_status_valid;

ALTER TABLE carts ADD CONSTRAINT ck_cart_status_valid
    CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'COMPLETED', 'ABANDONED'));
