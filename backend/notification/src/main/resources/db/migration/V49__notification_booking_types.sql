-- Ground booking notifications (Phase 1). Widens the allowed notification types; forward-only, no data is touched.
ALTER TABLE notifications DROP CONSTRAINT ck_notification_type_valid;
ALTER TABLE notifications ADD CONSTRAINT ck_notification_type_valid CHECK (type IN (
    'ORDER_PLACED', 'PAYMENT_SUCCESS', 'PAYMENT_FAILED', 'ORDER_CONFIRMED',
    'ORDER_SHIPPED', 'ORDER_DELIVERED', 'ORDER_CANCELLED', 'REFUND_COMPLETED',
    'BOOKING_CONFIRMED', 'BOOKING_CANCELLED'
));
