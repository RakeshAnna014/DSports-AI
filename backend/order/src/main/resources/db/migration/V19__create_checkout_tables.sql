CREATE TABLE checkouts (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    cart_id UUID NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    shipping_address_id UUID,
    delivery_method_code VARCHAR(30),
    delivery_method_name VARCHAR(100),
    delivery_charge DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    subtotal DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    tax_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    notes VARCHAR(500),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    validated_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE checkout_items (
    id UUID PRIMARY KEY,
    checkout_id UUID NOT NULL REFERENCES checkouts(id),
    product_id UUID NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(10,2) NOT NULL,
    line_total DECIMAL(10,2) NOT NULL,
    image_url VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_checkouts_customer_id ON checkouts(customer_id);
CREATE INDEX idx_checkouts_status ON checkouts(status);
CREATE INDEX idx_checkouts_expires_at ON checkouts(expires_at);
CREATE INDEX idx_checkout_items_checkout_id ON checkout_items(checkout_id);
