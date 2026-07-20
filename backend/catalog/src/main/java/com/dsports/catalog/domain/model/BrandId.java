package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public record BrandId(UUID value) implements ValueObject {

    public BrandId {
        Objects.requireNonNull(value, "brandId must not be null");
    }

    public static BrandId generate() {
        return new BrandId(UUID.randomUUID());
    }

    public static BrandId fromString(String value) {
        return new BrandId(UUID.fromString(value));
    }

    public static BrandId fromUUID(UUID value) {
        return new BrandId(value);
    }
}
