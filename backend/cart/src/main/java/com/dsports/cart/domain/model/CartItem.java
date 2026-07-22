package com.dsports.cart.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class CartItem {
    private final CartItemId id;
    private final CartId cartId;
    private final String productId;
    private final String productName;
    private Money unitPrice;
    private Quantity quantity;
    private Money lineTotal;
    private Instant createdAt;
    private Instant updatedAt;

    private CartItem(CartItemId id, CartId cartId, String productId, String productName,
                     Money unitPrice, Quantity quantity, Money lineTotal,
                     Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.cartId = cartId;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.lineTotal = lineTotal;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CartItem create(CartItemId id, CartId cartId, String productId, String productName,
                                  Money unitPrice, Quantity quantity) {
        var lineTotal = unitPrice.multiply(quantity.value());
        var now = Instant.now();
        return new CartItem(id, cartId, productId, productName, unitPrice, quantity, lineTotal, now, now);
    }

    public static CartItem reconstitute(CartItemId id, CartId cartId, String productId, String productName,
                                        Money unitPrice, Quantity quantity, Money lineTotal,
                                        Instant createdAt, Instant updatedAt) {
        return new CartItem(id, cartId, productId, productName, unitPrice, quantity, lineTotal, createdAt, updatedAt);
    }

    public void updateQuantity(Quantity newQuantity) {
        this.quantity = newQuantity;
        this.lineTotal = this.unitPrice.multiply(newQuantity.value());
        this.updatedAt = Instant.now();
    }

    public CartItemId getId() {
        return id;
    }

    public CartId getCartId() {
        return cartId;
    }

    public String getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public Money getUnitPrice() {
        return unitPrice;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public Money getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CartItem cartItem)) return false;
        return Objects.equals(id, cartItem.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CartItem{id=" + id + ", productId=" + productId + ", quantity=" + quantity + "}";
    }
}
