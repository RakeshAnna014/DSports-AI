package com.dsports.cart.domain.model;

import java.util.Objects;
import java.util.UUID;

public record UserId(UUID value) {
    public UserId {
        Objects.requireNonNull(value, "UserId must not be null");
    }

    public static UserId fromString(String value) {
        return new UserId(UUID.fromString(value));
    }

    public static UserId fromUUID(UUID value) {
        return new UserId(value);
    }
}
