CREATE TABLE carts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    total_items INTEGER NOT NULL DEFAULT 0,
    total_amount DECIMAL(10, 2) NOT NULL DEFAULT 0.00,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cart_status_valid CHECK (status IN ('ACTIVE', 'CHECKED_OUT', 'ABANDONED')),
    CONSTRAINT ck_cart_total_items_non_negative CHECK (total_items >= 0),
    CONSTRAINT ck_cart_total_amount_non_negative CHECK (total_amount >= 0.00)
);

CREATE TABLE cart_items (
    id UUID PRIMARY KEY,
    cart_id UUID NOT NULL,
    product_id UUID NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    quantity INTEGER NOT NULL,
    line_total DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_cart_items_cart_id FOREIGN KEY (cart_id) REFERENCES carts(id) ON DELETE CASCADE,
    CONSTRAINT ck_cart_item_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_cart_item_unit_price_non_negative CHECK (unit_price >= 0.00),
    CONSTRAINT ck_cart_item_line_total_non_negative CHECK (line_total >= 0.00)
);

CREATE UNIQUE INDEX uq_cart_items_cart_product ON cart_items(cart_id, product_id);
CREATE INDEX ix_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX ix_cart_items_product_id ON cart_items(product_id);
CREATE INDEX ix_carts_user_id ON carts(user_id);
CREATE INDEX ix_carts_status ON carts(status);
CREATE UNIQUE INDEX uq_carts_user_active ON carts(user_id) WHERE status = 'ACTIVE';
