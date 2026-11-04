-- One order per checkout: the database backs the application's duplicate check under concurrency.
DROP INDEX IF EXISTS idx_orders_checkout_id;
CREATE UNIQUE INDEX uq_orders_checkout_id ON orders (checkout_id);

-- Order numbers come from a sequence (unique under concurrency), continuing after existing orders.
CREATE SEQUENCE order_number_seq;
SELECT setval('order_number_seq', (SELECT COUNT(*) + 1 FROM orders), false);

-- Checkout lifecycle, now including COMPLETED (consumed by an order).
ALTER TABLE checkouts ADD CONSTRAINT ck_checkout_status_valid
    CHECK (status IN ('PENDING', 'VALIDATED', 'READY_FOR_PAYMENT', 'EXPIRED', 'CANCELLED', 'COMPLETED'));

-- Monetary sanity on order lines.
ALTER TABLE order_items ADD CONSTRAINT ck_order_item_prices_non_negative
    CHECK (unit_price >= 0 AND line_total >= 0);
ALTER TABLE orders ADD CONSTRAINT ck_order_currency_format CHECK (currency ~ '^[A-Z]{3}$');
