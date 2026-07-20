CREATE TABLE products (
    id UUID PRIMARY KEY,
    sku VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    brand_id UUID NOT NULL,
    category_id UUID NOT NULL,
    sport_id UUID NOT NULL,
    weight DECIMAL(10, 2),
    weight_unit VARCHAR(10),
    length DECIMAL(10, 2),
    width DECIMAL(10, 2),
    height DECIMAL(10, 2),
    dimension_unit VARCHAR(10),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT uq_products_slug UNIQUE (slug)
);

CREATE INDEX ix_products_brand_id ON products(brand_id);
CREATE INDEX ix_products_category_id ON products(category_id);
CREATE INDEX ix_products_sport_id ON products(sport_id);
CREATE INDEX ix_products_status ON products(status);
CREATE INDEX ix_products_created_at ON products(created_at);
