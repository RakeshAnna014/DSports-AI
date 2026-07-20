package com.dsports.pricing.domain.model;

import java.util.UUID;

public record PriceId(UUID value) {
    public PriceId {
        if (value == null) {
            throw new IllegalArgumentException("PriceId must not be null");
        }
    }

    public static PriceId generate() {
        return new PriceId(UUID.randomUUID());
    }

    public static PriceId fromUUID(UUID value) {
        return new PriceId(value);
    }
}
