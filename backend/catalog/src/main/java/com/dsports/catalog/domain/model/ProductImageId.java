package com.dsports.catalog.domain.model;

import java.util.UUID;

public record ProductImageId(UUID value) {
    public ProductImageId {
        if (value == null) {
            throw new IllegalArgumentException("ProductImageId must not be null");
        }
    }

    public static ProductImageId generate() {
        return new ProductImageId(UUID.randomUUID());
    }

    public static ProductImageId fromUUID(UUID value) {
        return new ProductImageId(value);
    }
}
