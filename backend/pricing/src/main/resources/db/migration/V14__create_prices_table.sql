CREATE TABLE prices (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    mrp DECIMAL(10, 2) NOT NULL,
    selling_price DECIMAL(10, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    effective_from TIMESTAMP NOT NULL,
    effective_to TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_price_selling_not_exceed_mrp CHECK (selling_price <= mrp),
    CONSTRAINT ck_price_mrp_positive CHECK (mrp >= 0),
    CONSTRAINT ck_price_selling_positive CHECK (selling_price >= 0),
    CONSTRAINT ck_price_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_price_status_valid CHECK (status IN ('DRAFT', 'ACTIVE', 'SCHEDULED', 'ARCHIVED'))
);

CREATE UNIQUE INDEX uq_price_product_currency_active
    ON prices(product_id, currency) WHERE status = 'ACTIVE';

CREATE INDEX ix_prices_product_id ON prices(product_id);
CREATE INDEX ix_prices_currency ON prices(currency);
CREATE INDEX ix_prices_status ON prices(status);
CREATE INDEX ix_prices_effective_from ON prices(effective_from);
