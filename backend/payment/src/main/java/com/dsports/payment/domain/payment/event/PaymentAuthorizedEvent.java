package com.dsports.payment.domain.payment.event;

import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public class PaymentAuthorizedEvent extends DomainEvent {
    private final PaymentId paymentId;
    private final UUID userId;

    public PaymentAuthorizedEvent(PaymentId paymentId, UUID userId) {
        this.paymentId = paymentId;
        this.userId = userId;
    }

    public PaymentId getPaymentId() { return paymentId; }
    public UUID getUserId() { return userId; }
}
