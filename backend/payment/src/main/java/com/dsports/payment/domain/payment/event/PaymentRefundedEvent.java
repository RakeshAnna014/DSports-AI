package com.dsports.payment.domain.payment.event;

import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentRefundedEvent extends DomainEvent {
    private final PaymentId paymentId;
    private final UUID orderId;
    private final UUID userId;
    private final BigDecimal refundAmount;

    public PaymentRefundedEvent(PaymentId paymentId, UUID orderId, UUID userId, BigDecimal refundAmount) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.userId = userId;
        this.refundAmount = refundAmount;
    }

    public PaymentId getPaymentId() { return paymentId; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getRefundAmount() { return refundAmount; }
}
