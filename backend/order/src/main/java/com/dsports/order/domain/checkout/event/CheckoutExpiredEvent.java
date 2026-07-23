package com.dsports.order.domain.checkout.event;

import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public final class CheckoutExpiredEvent extends DomainEvent {
    private final CheckoutId checkoutId;
    private final UUID customerId;

    public CheckoutExpiredEvent(CheckoutId checkoutId, UUID customerId) {
        this.checkoutId = checkoutId;
        this.customerId = customerId;
    }

    public CheckoutId getCheckoutId() { return checkoutId; }
    public UUID getCustomerId() { return customerId; }
}
