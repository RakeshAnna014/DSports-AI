package com.dsports.order.domain.checkout.event;

import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

public final class CheckoutValidatedEvent extends DomainEvent {
    private final CheckoutId checkoutId;
    private final UUID customerId;
    private final BigDecimal totalAmount;

    public CheckoutValidatedEvent(CheckoutId checkoutId, UUID customerId, BigDecimal totalAmount) {
        this.checkoutId = checkoutId;
        this.customerId = customerId;
        this.totalAmount = totalAmount;
    }

    public CheckoutId getCheckoutId() { return checkoutId; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
}
