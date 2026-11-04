package com.dsports.wishlist.domain.event;

import com.dsports.shared.domain.kernel.DomainEvent;
import com.dsports.wishlist.domain.model.WishlistId;
import com.dsports.wishlist.domain.model.WishlistItemId;

import java.math.BigDecimal;

public final class WishlistItemMovedToCartEvent extends DomainEvent {
    private final WishlistId wishlistId;
    private final WishlistItemId itemId;
    private final String productId;
    private final String productName;
    private final BigDecimal currentPrice;

    public WishlistItemMovedToCartEvent(WishlistId wishlistId, WishlistItemId itemId,
                                        String productId, String productName,
                                        BigDecimal currentPrice) {
        this.wishlistId = wishlistId;
        this.itemId = itemId;
        this.productId = productId;
        this.productName = productName;
        this.currentPrice = currentPrice;
    }

    public WishlistId getWishlistId() { return wishlistId; }
    public WishlistItemId getItemId() { return itemId; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getCurrentPrice() { return currentPrice; }
}
