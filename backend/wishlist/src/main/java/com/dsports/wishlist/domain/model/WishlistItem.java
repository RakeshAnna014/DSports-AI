package com.dsports.wishlist.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class WishlistItem {
    private final WishlistItemId id;
    private final WishlistId wishlistId;
    private final String productId;
    private final String productName;
    private final String productImage;
    private Price currentPrice;
    private Instant addedAt;

    private WishlistItem(WishlistItemId id, WishlistId wishlistId, String productId,
                         String productName, String productImage, Price currentPrice,
                         Instant addedAt) {
        this.id = id;
        this.wishlistId = wishlistId;
        this.productId = productId;
        this.productName = productName;
        this.productImage = productImage;
        this.currentPrice = currentPrice;
        this.addedAt = addedAt;
    }

    public static WishlistItem create(WishlistItemId id, WishlistId wishlistId,
                                      String productId, String productName,
                                      String productImage, Price currentPrice) {
        return new WishlistItem(id, wishlistId, productId, productName, productImage,
            currentPrice, Instant.now());
    }

    public static WishlistItem reconstitute(WishlistItemId id, WishlistId wishlistId,
                                            String productId, String productName,
                                            String productImage, BigDecimal currentPrice,
                                            Instant addedAt) {
        return new WishlistItem(id, wishlistId, productId, productName, productImage,
            Price.from(currentPrice), addedAt);
    }

    public WishlistItemId getId() { return id; }
    public WishlistId getWishlistId() { return wishlistId; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getProductImage() { return productImage; }
    public Price getCurrentPrice() { return currentPrice; }
    public Instant getAddedAt() { return addedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WishlistItem that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "WishlistItem{id=" + id + ", productId=" + productId + "}";
    }
}
