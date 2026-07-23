package com.dsports.order.domain.checkout.event;

import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;

public final class CheckoutDeliveryMethodSelectedEvent extends DomainEvent {
    private final CheckoutId checkoutId;
    private final String deliveryMethodCode;
    private final BigDecimal deliveryCharge;

    public CheckoutDeliveryMethodSelectedEvent(CheckoutId checkoutId, String deliveryMethodCode, BigDecimal deliveryCharge) {
        this.checkoutId = checkoutId;
        this.deliveryMethodCode = deliveryMethodCode;
        this.deliveryCharge = deliveryCharge;
    }

    public CheckoutId getCheckoutId() { return checkoutId; }
    public String getDeliveryMethodCode() { return deliveryMethodCode; }
    public BigDecimal getDeliveryCharge() { return deliveryCharge; }
}
