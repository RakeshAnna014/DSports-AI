package com.dsports.cart.domain.model;

import java.util.Objects;
import java.util.UUID;

public record CartItemId(UUID value) {
    public CartItemId {
        Objects.requireNonNull(value, "CartItemId must not be null");
    }

    public static CartItemId generate() {
        return new CartItemId(UUID.randomUUID());
    }

    public static CartItemId fromUUID(UUID value) {
        return new CartItemId(value);
    }
}
