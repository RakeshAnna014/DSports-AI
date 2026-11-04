package com.dsports.wishlist.domain.model;

import java.util.Objects;
import java.util.UUID;

public record WishlistId(UUID value) {
    public WishlistId {
        Objects.requireNonNull(value, "WishlistId must not be null");
    }

    public static WishlistId generate() {
        return new WishlistId(UUID.randomUUID());
    }

    public static WishlistId fromUUID(UUID value) {
        return new WishlistId(value);
    }
}
