package com.dsports.order.domain.checkout.event;

import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public final class CheckoutCreatedEvent extends DomainEvent {
    private final CheckoutId checkoutId;
    private final UUID customerId;
    private final UUID cartId;

    public CheckoutCreatedEvent(CheckoutId checkoutId, UUID customerId, UUID cartId) {
        this.checkoutId = checkoutId;
        this.customerId = customerId;
        this.cartId = cartId;
    }

    public CheckoutId getCheckoutId() { return checkoutId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getCartId() { return cartId; }
}
