-- Promotions: configurable, auditable offers. The backend alone decides validity and the discount amount.
-- Seeded rows are DEMO offers (synthetic, demo = TRUE) and must never be presented as real promotions.

CREATE TABLE promotions (
    id                  UUID PRIMARY KEY,
    code                VARCHAR(40)   NOT NULL,
    title               VARCHAR(120)  NOT NULL,
    description         VARCHAR(500)  NOT NULL,
    discount_type       VARCHAR(10)   NOT NULL,
    discount_value      DECIMAL(10,2) NOT NULL,
    max_discount_amount DECIMAL(10,2),
    min_order_amount    DECIMAL(10,2) NOT NULL DEFAULT 0,
    scope_type          VARCHAR(10)   NOT NULL DEFAULT 'ALL',
    valid_from          TIMESTAMP WITH TIME ZONE,
    valid_until         TIMESTAMP WITH TIME ZONE,
    per_customer_limit  INT,
    total_usage_limit   INT,
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    demo                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_promotion_type        CHECK (discount_type IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_promotion_value       CHECK (discount_value > 0),
    CONSTRAINT ck_promotion_percent     CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
    CONSTRAINT ck_promotion_max         CHECK (max_discount_amount IS NULL OR max_discount_amount > 0),
    CONSTRAINT ck_promotion_min         CHECK (min_order_amount >= 0),
    CONSTRAINT ck_promotion_scope       CHECK (scope_type IN ('ALL', 'CATEGORY', 'SPORT')),
    CONSTRAINT ck_promotion_window      CHECK (valid_from IS NULL OR valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_promotion_customer_lim CHECK (per_customer_limit IS NULL OR per_customer_limit > 0),
    CONSTRAINT ck_promotion_total_lim   CHECK (total_usage_limit IS NULL OR total_usage_limit > 0)
);
CREATE UNIQUE INDEX uq_promotions_code ON promotions (UPPER(code));

-- Category or sport ids a scoped promotion applies to (empty for scope_type = 'ALL').
CREATE TABLE promotion_scopes (
    promotion_id UUID NOT NULL REFERENCES promotions(id) ON DELETE CASCADE,
    scope_id     UUID NOT NULL,
    PRIMARY KEY (promotion_id, scope_id)
);

-- One row per redeemed checkout. Written in the same transaction as the order; cancelled orders stop counting against the limits.
CREATE TABLE promotion_redemptions (
    id              UUID PRIMARY KEY,
    promotion_id    UUID          NOT NULL REFERENCES promotions(id),
    customer_id     UUID          NOT NULL,
    checkout_id     UUID          NOT NULL,
    order_id        UUID,
    discount_amount DECIMAL(10,2) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_redemption_promotion_checkout UNIQUE (promotion_id, checkout_id),
    CONSTRAINT ck_redemption_discount CHECK (discount_amount >= 0)
);
CREATE INDEX idx_redemptions_promotion_customer ON promotion_redemptions (promotion_id, customer_id);
CREATE INDEX idx_redemptions_order ON promotion_redemptions (order_id);

ALTER TABLE checkouts ADD COLUMN promotion_id UUID;
ALTER TABLE checkouts ADD COLUMN promotion_code VARCHAR(40);
ALTER TABLE orders ADD COLUMN promotion_code VARCHAR(40);

-- DEMO offers (synthetic). Fixed ids keep the seed repeatable.
INSERT INTO promotions (id, code, title, description, discount_type, discount_value, max_discount_amount, min_order_amount, scope_type,
                        valid_from, valid_until, per_customer_limit, total_usage_limit, active, demo) VALUES
 ('00000000-0000-0000-0000-00000000d001', 'WELCOME10', 'Welcome offer',
  'DEMO OFFER (synthetic): 10% off your order, up to INR 300. Minimum eligible spend INR 500.',
  'PERCENT', 10, 300, 500, 'ALL', NULL, TIMESTAMP WITH TIME ZONE '2030-12-31 23:59:59+00', 1, NULL, TRUE, TRUE),
 ('00000000-0000-0000-0000-00000000d002', 'CRICKET15', 'Cricket gear offer',
  'DEMO OFFER (synthetic): 15% off cricket items, up to INR 750. Minimum eligible spend INR 1000.',
  'PERCENT', 15, 750, 1000, 'SPORT', NULL, TIMESTAMP WITH TIME ZONE '2030-12-31 23:59:59+00', 2, NULL, TRUE, TRUE),
 ('00000000-0000-0000-0000-00000000d003', 'FLAT200', 'Flat INR 200 off',
  'DEMO OFFER (synthetic): INR 200 off orders with at least INR 3000 of eligible items.',
  'FIXED', 200, NULL, 3000, 'ALL', NULL, TIMESTAMP WITH TIME ZONE '2030-12-31 23:59:59+00', 3, 1000, TRUE, TRUE),
 ('00000000-0000-0000-0000-00000000d004', 'OLDFEST5', 'Past festival offer',
  'DEMO OFFER (synthetic, already expired): kept so the expiry message can be demonstrated.',
  'PERCENT', 5, NULL, 0, 'ALL', TIMESTAMP WITH TIME ZONE '2025-01-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2025-01-31 23:59:59+00', NULL, NULL, TRUE, TRUE);

-- The catalogue lives in another module's migrations; when this module is migrated on its own (module tests) there is no sports table.
DO $$
BEGIN
    IF to_regclass('public.sports') IS NOT NULL THEN
        INSERT INTO promotion_scopes (promotion_id, scope_id)
        SELECT '00000000-0000-0000-0000-00000000d002', id FROM sports WHERE name = 'Cricket';
    END IF;
END $$;
