package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public record CategoryId(UUID value) implements ValueObject {

    public CategoryId {
        Objects.requireNonNull(value, "categoryId must not be null");
    }

    public static CategoryId generate() {
        return new CategoryId(UUID.randomUUID());
    }

    public static CategoryId fromString(String value) {
        return new CategoryId(UUID.fromString(value));
    }

    public static CategoryId fromUUID(UUID value) {
        return new CategoryId(value);
    }
}
