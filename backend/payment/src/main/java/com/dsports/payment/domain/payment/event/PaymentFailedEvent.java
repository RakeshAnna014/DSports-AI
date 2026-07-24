package com.dsports.payment.domain.payment.event;

import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public class PaymentFailedEvent extends DomainEvent {
    private final PaymentId paymentId;
    private final UUID userId;
    private final String failureReason;

    public PaymentFailedEvent(PaymentId paymentId, UUID userId, String failureReason) {
        this.paymentId = paymentId;
        this.userId = userId;
        this.failureReason = failureReason;
    }

    public PaymentId getPaymentId() { return paymentId; }
    public UUID getUserId() { return userId; }
    public String getFailureReason() { return failureReason; }
}
