package com.dsports.order.domain.checkout.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class CheckoutItem {
    private final CheckoutItemId id;
    private final CheckoutId checkoutId;
    private final String productId;
    private final String productName;
    private final String sku;
    private final int quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal lineTotal;
    private final String imageUrl;
    private final Instant createdAt;

    public CheckoutItem(CheckoutItemId id, CheckoutId checkoutId, String productId, String productName,
                        String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                        String imageUrl, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.checkoutId = Objects.requireNonNull(checkoutId);
        this.productId = Objects.requireNonNull(productId);
        this.productName = Objects.requireNonNull(productName);
        this.sku = Objects.requireNonNull(sku);
        this.quantity = quantity;
        this.unitPrice = Objects.requireNonNull(unitPrice);
        this.lineTotal = Objects.requireNonNull(lineTotal);
        this.imageUrl = imageUrl;
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public CheckoutItemId getId() { return id; }
    public CheckoutId getCheckoutId() { return checkoutId; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public String getImageUrl() { return imageUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
