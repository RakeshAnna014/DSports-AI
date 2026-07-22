package com.dsports.cart.domain.event;

import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;

public final class ProductAddedToCartEvent extends DomainEvent {
    private final CartId cartId;
    private final CartItemId itemId;
    private final String productId;
    private final String productName;
    private final BigDecimal unitPrice;
    private final int quantity;

    public ProductAddedToCartEvent(CartId cartId, CartItemId itemId, String productId,
                                   String productName, BigDecimal unitPrice, int quantity) {
        this.cartId = cartId;
        this.itemId = itemId;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public CartId getCartId() { return cartId; }
    public CartItemId getItemId() { return itemId; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
}
