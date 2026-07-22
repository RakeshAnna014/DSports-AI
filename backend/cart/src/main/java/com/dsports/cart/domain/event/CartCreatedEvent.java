package com.dsports.cart.domain.event;

import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.UserId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class CartCreatedEvent extends DomainEvent {
    private final CartId cartId;
    private final UserId userId;

    public CartCreatedEvent(CartId cartId, UserId userId) {
        this.cartId = cartId;
        this.userId = userId;
    }

    public CartId getCartId() { return cartId; }
    public UserId getUserId() { return userId; }
}
