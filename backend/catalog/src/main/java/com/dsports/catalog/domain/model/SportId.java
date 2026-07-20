package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.UUID;

public record SportId(UUID value) implements ValueObject {

    public SportId {
        Objects.requireNonNull(value, "sportId must not be null");
    }

    public static SportId generate() {
        return new SportId(UUID.randomUUID());
    }

    public static SportId fromString(String value) {
        return new SportId(UUID.fromString(value));
    }

    public static SportId fromUUID(UUID value) {
        return new SportId(value);
    }
}
