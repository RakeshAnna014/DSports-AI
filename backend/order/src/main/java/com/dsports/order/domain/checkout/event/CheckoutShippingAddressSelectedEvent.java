package com.dsports.order.domain.checkout.event;

import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public final class CheckoutShippingAddressSelectedEvent extends DomainEvent {
    private final CheckoutId checkoutId;
    private final UUID addressId;

    public CheckoutShippingAddressSelectedEvent(CheckoutId checkoutId, UUID addressId) {
        this.checkoutId = checkoutId;
        this.addressId = addressId;
    }

    public CheckoutId getCheckoutId() { return checkoutId; }
    public UUID getAddressId() { return addressId; }
}
