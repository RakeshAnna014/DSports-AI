package com.dsports.cart.domain.event;

import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.CartItemId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class CartItemRemovedEvent extends DomainEvent {
    private final CartId cartId;
    private final CartItemId itemId;
    private final String productId;

    public CartItemRemovedEvent(CartId cartId, CartItemId itemId, String productId) {
        this.cartId = cartId;
        this.itemId = itemId;
        this.productId = productId;
    }

    public CartId getCartId() { return cartId; }
    public CartItemId getItemId() { return itemId; }
    public String getProductId() { return productId; }
}
