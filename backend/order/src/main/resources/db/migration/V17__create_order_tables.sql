CREATE TABLE orders (
    id UUID PRIMARY KEY,
    order_number VARCHAR(20) NOT NULL,
    user_id UUID NOT NULL,
    checkout_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    shipping_address_snapshot TEXT,
    billing_address_snapshot TEXT,
    subtotal DECIMAL(12, 2) NOT NULL DEFAULT 0,
    shipping_charge DECIMAL(12, 2) NOT NULL DEFAULT 0,
    tax_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    discount_amount DECIMAL(12, 2) NOT NULL DEFAULT 0,
    grand_total DECIMAL(12, 2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    placed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_order_status_valid CHECK (status IN (
        'CREATED', 'PENDING_PAYMENT', 'CONFIRMED', 'PROCESSING',
        'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'
    ))
);

CREATE TABLE order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    product_id UUID NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(12, 2) NOT NULL,
    line_total DECIMAL(12, 2) NOT NULL,
    product_image VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_orders_order_number ON orders(order_number);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_placed_at ON orders(placed_at);
CREATE INDEX idx_orders_checkout_id ON orders(checkout_id);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
