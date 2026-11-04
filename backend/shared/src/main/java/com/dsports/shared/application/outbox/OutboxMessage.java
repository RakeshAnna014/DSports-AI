package com.dsports.shared.application.outbox;

import java.util.Map;
import java.util.Objects;

/**
 * A unit of follow-up work recorded in the same transaction as the business change that requires it.
 * <p>
 * {@code (type, aggregateId)} identifies the work: enqueuing the same pair twice is a no-op, which makes
 * retried business operations safe.
 */
public record OutboxMessage(String type, String aggregateId, Map<String, String> payload) {

    public OutboxMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(aggregateId, "aggregateId");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static OutboxMessage of(String type, Object aggregateId, Map<String, String> payload) {
        return new OutboxMessage(type, String.valueOf(aggregateId), payload);
    }
}
