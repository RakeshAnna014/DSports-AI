package com.dsports.wishlist.domain.event;

import com.dsports.shared.domain.kernel.DomainEvent;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.domain.model.WishlistItemId;

public final class WishlistItemRemovedEvent extends DomainEvent {
    private final WishlistId wishlistId;
    private final WishlistItemId itemId;
    private final String productId;

    public WishlistItemRemovedEvent(WishlistId wishlistId, WishlistItemId itemId,
                                    String productId) {
        this.wishlistId = wishlistId;
        this.itemId = itemId;
        this.productId = productId;
    }

    public WishlistId getWishlistId() { return wishlistId; }
    public WishlistItemId getItemId() { return itemId; }
    public String getProductId() { return productId; }
}
