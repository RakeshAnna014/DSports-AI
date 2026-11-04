-- A business event notifies a user once: retried deliveries of the same outbox message cannot duplicate it.
CREATE UNIQUE INDEX uq_notifications_user_type_reference ON notifications (user_id, type, reference_id)
    WHERE reference_id IS NOT NULL;
