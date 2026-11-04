package com.dsports.wishlist.domain.event;

import com.dsports.shared.domain.kernel.DomainEvent;
import com.dsports.wishlist.domain.model.UserId;
import com.dsports.wishlist.domain.model.WishlistId;

public final class WishlistCreatedEvent extends DomainEvent {
    private final WishlistId wishlistId;
    private final UserId userId;

    public WishlistCreatedEvent(WishlistId wishlistId, UserId userId) {
        this.wishlistId = wishlistId;
        this.userId = userId;
    }

    public WishlistId getWishlistId() { return wishlistId; }
    public UserId getUserId() { return userId; }
}
