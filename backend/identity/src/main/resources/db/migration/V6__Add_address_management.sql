CREATE TABLE IF NOT EXISTS customer_addresses (
    id UUID NOT NULL PRIMARY KEY,
    customer_id UUID NOT NULL,
    type VARCHAR(20) NOT NULL,
    line1 VARCHAR(255) NOT NULL,
    line2 VARCHAR(255),
    city VARCHAR(255) NOT NULL,
    state VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_customer_addresses_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);

CREATE INDEX IF NOT EXISTS idx_customer_addresses_customer_id ON customer_addresses(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_addresses_customer_type ON customer_addresses(customer_id, type);
CREATE INDEX IF NOT EXISTS idx_customer_addresses_customer_default ON customer_addresses(customer_id, is_default);

ALTER TABLE customers ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
