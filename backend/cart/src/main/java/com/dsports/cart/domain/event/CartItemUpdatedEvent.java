package com.dsports.cart.domain.event;

import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.cart.domain.model.Quantity;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class CartItemUpdatedEvent extends DomainEvent {
    private final CartId cartId;
    private final CartItemId itemId;
    private final String productId;
    private final Quantity newQuantity;

    public CartItemUpdatedEvent(CartId cartId, CartItemId itemId, String productId, Quantity newQuantity) {
        this.cartId = cartId;
        this.itemId = itemId;
        this.productId = productId;
        this.newQuantity = newQuantity;
    }

    public CartId getCartId() { return cartId; }
    public CartItemId getItemId() { return itemId; }
    public String getProductId() { return productId; }
    public Quantity getNewQuantity() { return newQuantity; }
}
