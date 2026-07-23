package com.dsports.order.domain.checkout.model;

import java.util.Objects;
import java.util.UUID;

public record CheckoutId(UUID value) {
    public CheckoutId {
        Objects.requireNonNull(value, "CheckoutId must not be null");
    }

    public static CheckoutId generate() {
        return new CheckoutId(UUID.randomUUID());
    }

    public static CheckoutId fromUUID(UUID value) {
        return new CheckoutId(value);
    }
}
