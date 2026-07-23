package com.dsports.order.domain.order.model;

import java.util.Objects;
import java.util.UUID;

public record OrderItemId(UUID value) {
    public OrderItemId {
        Objects.requireNonNull(value, "OrderItemId must not be null");
    }

    public static OrderItemId generate() {
        return new OrderItemId(UUID.randomUUID());
    }

    public static OrderItemId fromUUID(UUID uuid) {
        return new OrderItemId(uuid);
    }
}
