package com.dsports.order.domain.order.model;

import java.util.Objects;
import java.util.UUID;

public record OrderId(UUID value) {
    public OrderId {
        Objects.requireNonNull(value, "OrderId must not be null");
    }

    public static OrderId generate() {
        return new OrderId(UUID.randomUUID());
    }

    public static OrderId fromUUID(UUID uuid) {
        return new OrderId(uuid);
    }
}
