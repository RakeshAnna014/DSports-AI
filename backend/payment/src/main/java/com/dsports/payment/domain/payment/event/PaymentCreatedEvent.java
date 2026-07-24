package com.dsports.payment.domain.payment.event;

import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentReference;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

public class PaymentCreatedEvent extends DomainEvent {
    private final PaymentId paymentId;
    private final PaymentReference paymentReference;
    private final UUID orderId;
    private final UUID userId;
    private final BigDecimal amount;
    private final String currency;

    public PaymentCreatedEvent(PaymentId paymentId, PaymentReference paymentReference,
                                UUID orderId, UUID userId, BigDecimal amount, String currency) {
        this.paymentId = paymentId;
        this.paymentReference = paymentReference;
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
    }

    public PaymentId getPaymentId() { return paymentId; }
    public PaymentReference getPaymentReference() { return paymentReference; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
}
