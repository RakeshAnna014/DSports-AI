-- Structured product facts (skill level, material, surface, ...) owned by the catalog. Read publicly, written by admins.
CREATE TABLE product_attributes (
    product_id UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    attr_key   VARCHAR(50)  NOT NULL,
    attr_value VARCHAR(200) NOT NULL,
    PRIMARY KEY (product_id, attr_key),
    CONSTRAINT ck_product_attr_key_format CHECK (attr_key ~ '^[a-zA-Z][a-zA-Z0-9_]*$')
);

-- Free-text product search (?q=) matches name/description/SKU case-insensitively.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_products_name_trgm ON products USING gin (name gin_trgm_ops);
CREATE INDEX ix_products_description_trgm ON products USING gin (description gin_trgm_ops);
