package com.dsports.pricing.domain.model;

import java.util.UUID;

public record ProductId(UUID value) {
    public ProductId {
        if (value == null) {
            throw new IllegalArgumentException("ProductId must not be null");
        }
    }

    public static ProductId fromUUID(UUID value) {
        return new ProductId(value);
    }
}
