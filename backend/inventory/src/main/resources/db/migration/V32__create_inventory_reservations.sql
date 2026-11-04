-- Stock invariants enforced by the database as the last line of defence against overselling.
-- available_quantity = units on hand; reserved_quantity = part of those held for unpaid orders.
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_available_non_negative CHECK (available_quantity >= 0);
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_reserved_non_negative CHECK (reserved_quantity >= 0);
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_reserved_within_available CHECK (reserved_quantity <= available_quantity);
ALTER TABLE inventory ADD CONSTRAINT ck_inventory_status_valid CHECK (status IN ('IN_STOCK', 'LOW_STOCK', 'OUT_OF_STOCK'));

-- One row per order line holding stock: RESERVED -> FULFILLED (paid) | RELEASED (cancelled/expired).
CREATE TABLE inventory_reservations (
    id           UUID PRIMARY KEY,
    order_id     UUID        NOT NULL,
    product_id   UUID        NOT NULL,
    inventory_id UUID        NOT NULL REFERENCES inventory (id),
    quantity     INTEGER     NOT NULL,
    status       VARCHAR(20) NOT NULL,
    version      INTEGER     NOT NULL DEFAULT 1,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reservation_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservation_status_valid CHECK (status IN ('RESERVED', 'FULFILLED', 'RELEASED')),
    CONSTRAINT uq_reservation_order_product UNIQUE (order_id, product_id)
);

CREATE INDEX ix_reservations_inventory_id ON inventory_reservations (inventory_id);
CREATE INDEX ix_reservations_active ON inventory_reservations (status) WHERE status = 'RESERVED';
