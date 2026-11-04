package com.dsports.wishlist.domain.model;

import java.util.Objects;
import java.util.UUID;

public record WishlistItemId(UUID value) {
    public WishlistItemId {
        Objects.requireNonNull(value, "WishlistItemId must not be null");
    }

    public static WishlistItemId generate() {
        return new WishlistItemId(UUID.randomUUID());
    }

    public static WishlistItemId fromUUID(UUID value) {
        return new WishlistItemId(value);
    }
}
