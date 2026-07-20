package com.dsports.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ProductId(UUID value) {
    public ProductId {
        Objects.requireNonNull(value, "productId must not be null");
    }

    public static ProductId generate() {
        return new ProductId(UUID.randomUUID());
    }

    public static ProductId fromUUID(UUID value) {
        return new ProductId(value);
    }
}
