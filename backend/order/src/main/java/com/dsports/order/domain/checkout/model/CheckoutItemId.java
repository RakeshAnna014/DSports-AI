package com.dsports.order.domain.checkout.model;

import java.util.Objects;
import java.util.UUID;

public record CheckoutItemId(UUID value) {
    public CheckoutItemId {
        Objects.requireNonNull(value, "CheckoutItemId must not be null");
    }

    public static CheckoutItemId generate() {
        return new CheckoutItemId(UUID.randomUUID());
    }

    public static CheckoutItemId fromUUID(UUID value) {
        return new CheckoutItemId(value);
    }
}
