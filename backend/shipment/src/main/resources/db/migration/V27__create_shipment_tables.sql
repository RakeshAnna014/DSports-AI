CREATE TABLE shipments (
    id UUID PRIMARY KEY,
    shipment_number VARCHAR(20) NOT NULL,
    tracking_number VARCHAR(20),
    order_id UUID NOT NULL,
    user_id UUID NOT NULL,
    courier_provider VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    street VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    zip_code VARCHAR(20) NOT NULL,
    country VARCHAR(100) NOT NULL,
    estimated_delivery_date TIMESTAMP WITH TIME ZONE,
    actual_delivery_date TIMESTAMP WITH TIME ZONE,
    shipped_at TIMESTAMP WITH TIME ZONE,
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version INT NOT NULL DEFAULT 0,

    CONSTRAINT chk_shipment_status CHECK (status IN (
        'CREATED', 'READY_FOR_PICKUP', 'PICKED_UP', 'IN_TRANSIT',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'RETURNED'
    )),
    CONSTRAINT chk_courier_provider CHECK (courier_provider IN ('MOCK'))
);

CREATE TABLE shipment_tracking (
    id UUID PRIMARY KEY,
    shipment_id UUID NOT NULL REFERENCES shipments(id),
    status VARCHAR(20) NOT NULL,
    location VARCHAR(255),
    description TEXT NOT NULL,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT chk_tracking_status CHECK (status IN (
        'CREATED', 'READY_FOR_PICKUP', 'PICKED_UP', 'IN_TRANSIT',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'RETURNED'
    ))
);

CREATE INDEX idx_shipments_shipment_number ON shipments(shipment_number);
CREATE UNIQUE INDEX idx_shipments_tracking_number ON shipments(tracking_number) WHERE tracking_number IS NOT NULL;
CREATE INDEX idx_shipments_order_id ON shipments(order_id);
CREATE INDEX idx_shipments_user_id ON shipments(user_id);
CREATE INDEX idx_shipments_status ON shipments(status);
CREATE INDEX idx_shipment_tracking_shipment_id ON shipment_tracking(shipment_id);
