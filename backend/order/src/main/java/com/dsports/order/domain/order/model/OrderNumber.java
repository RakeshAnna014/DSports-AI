package com.dsports.order.domain.order.model;

import java.util.Objects;

public record OrderNumber(String value) {
    public OrderNumber {
        Objects.requireNonNull(value, "OrderNumber must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("OrderNumber must not be blank");
        }
    }

    public static OrderNumber generate(long sequence) {
        var timestamp = java.time.Instant.now();
        var formatted = String.format("ORD-%tY%<tm%<td-%06d",
            java.util.Date.from(timestamp), sequence % 1_000_000);
        return new OrderNumber(formatted);
    }
}
