-- An order has at most one shipment; shipment numbers are unique identifiers.
DROP INDEX IF EXISTS idx_shipments_order_id;
CREATE UNIQUE INDEX uq_shipments_order_id ON shipments (order_id);
DROP INDEX IF EXISTS idx_shipments_shipment_number;
CREATE UNIQUE INDEX uq_shipments_shipment_number ON shipments (shipment_number);
