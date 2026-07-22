package com.dsports.cart.domain.model;

import java.util.Objects;
import java.util.UUID;

public record CartId(UUID value) {
    public CartId {
        Objects.requireNonNull(value, "CartId must not be null");
    }

    public static CartId generate() {
        return new CartId(UUID.randomUUID());
    }

    public static CartId fromUUID(UUID value) {
        return new CartId(value);
    }
}
