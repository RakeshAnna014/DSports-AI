package com.dsports.payment.domain.payment.model;

import com.dsports.payment.domain.payment.event.PaymentAuthorizedEvent;
import com.dsports.payment.domain.payment.event.PaymentCancelledEvent;
import com.dsports.payment.domain.payment.event.PaymentCreatedEvent;
import com.dsports.payment.domain.payment.event.PaymentFailedEvent;
import com.dsports.payment.domain.payment.event.PaymentRefundedEvent;
import com.dsports.payment.domain.payment.event.PaymentSucceededEvent;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Payment {
    private final PaymentId id;
    private PaymentReference paymentReference;
    private final UUID orderId;
    private final UUID userId;
    private BigDecimal amount;
    private String currency;
    private PaymentMethod paymentMethod;
    private PaymentProvider paymentProvider;
    private String transactionId;
    private String gatewayReference;
    private PaymentStatus status;
    private String failureReason;
    private Instant paidAt;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Payment(PaymentId id, PaymentReference paymentReference, UUID orderId, UUID userId,
                    BigDecimal amount, String currency, PaymentMethod paymentMethod,
                    PaymentProvider paymentProvider, String transactionId, String gatewayReference,
                    PaymentStatus status, String failureReason, Instant paidAt,
                    int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.paymentReference = paymentReference;
        this.orderId = Objects.requireNonNull(orderId);
        this.userId = Objects.requireNonNull(userId);
        this.amount = amount;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.paymentProvider = paymentProvider;
        this.transactionId = transactionId;
        this.gatewayReference = gatewayReference;
        this.status = Objects.requireNonNull(status);
        this.failureReason = failureReason;
        this.paidAt = paidAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Payment create(PaymentId id, PaymentReference paymentReference, UUID orderId, UUID userId,
                                  BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentDomainException(PaymentErrorCode.AMOUNT_MISMATCH,
                "Payment amount must be positive");
        }
        var now = Instant.now();
        var payment = new Payment(id, paymentReference, orderId, userId,
            amount, currency, null, null, null, null,
            PaymentStatus.CREATED, null, null,
            0, now, now);
        payment.domainEvents.add(new PaymentCreatedEvent(id, paymentReference, orderId, userId, amount, currency));
        return payment;
    }

    public static Payment reconstitute(PaymentId id, PaymentReference paymentReference, UUID orderId, UUID userId,
                                        BigDecimal amount, String currency, PaymentMethod paymentMethod,
                                        PaymentProvider paymentProvider, String transactionId, String gatewayReference,
                                        PaymentStatus status, String failureReason, Instant paidAt,
                                        int version, Instant createdAt, Instant updatedAt) {
        return new Payment(id, paymentReference, orderId, userId,
            amount, currency, paymentMethod, paymentProvider, transactionId, gatewayReference,
            status, failureReason, paidAt, version, createdAt, updatedAt);
    }

    public void initiatePayment(PaymentMethod paymentMethod, PaymentProvider paymentProvider) {
        if (status != PaymentStatus.CREATED) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot initiate payment in status: " + status);
        }
        this.paymentMethod = Objects.requireNonNull(paymentMethod);
        this.paymentProvider = Objects.requireNonNull(paymentProvider);
        transitionTo(PaymentStatus.PENDING);
        this.updatedAt = Instant.now();
    }

    public void authorize(String transactionId, String gatewayReference) {
        if (status != PaymentStatus.PENDING) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot authorize payment in status: " + status);
        }
        this.transactionId = Objects.requireNonNull(transactionId);
        this.gatewayReference = Objects.requireNonNull(gatewayReference);
        transitionTo(PaymentStatus.AUTHORIZED);
        this.updatedAt = Instant.now();
        domainEvents.add(new PaymentAuthorizedEvent(id, userId));
    }

    public void markSuccess(String transactionId, String gatewayReference) {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.AUTHORIZED) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot mark payment successful in status: " + status);
        }
        this.transactionId = Objects.requireNonNull(transactionId);
        this.gatewayReference = Objects.requireNonNull(gatewayReference);
        this.paidAt = Instant.now();
        transitionTo(PaymentStatus.SUCCESS);
        this.updatedAt = Instant.now();
        domainEvents.add(new PaymentSucceededEvent(id, paymentReference, orderId, userId,
            amount, currency, transactionId, gatewayReference));
    }

    public void markFailed(String failureReason) {
        if (status.isTerminal() && status != PaymentStatus.FAILED) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot mark payment failed in terminal status: " + status);
        }
        this.failureReason = Objects.requireNonNull(failureReason);
        transitionTo(PaymentStatus.FAILED);
        this.updatedAt = Instant.now();
        domainEvents.add(new PaymentFailedEvent(id, userId, failureReason));
    }

    public void cancel() {
        if (status == PaymentStatus.SUCCESS) {
            throw new PaymentDomainException(PaymentErrorCode.PAYMENT_ALREADY_SUCCESSFUL,
                "Cannot cancel a successful payment");
        }
        if (status == PaymentStatus.REFUNDED) {
            throw new PaymentDomainException(PaymentErrorCode.PAYMENT_ALREADY_REFUNDED,
                "Cannot cancel a refunded payment");
        }
        if (status.isTerminal()) {
            throw new PaymentDomainException(PaymentErrorCode.CANNOT_CANCEL_TERMINAL_PAYMENT,
                "Cannot cancel payment in terminal status: " + status);
        }
        transitionTo(PaymentStatus.CANCELLED);
        this.updatedAt = Instant.now();
        domainEvents.add(new PaymentCancelledEvent(id, userId));
    }

    public void refund() {
        if (status != PaymentStatus.SUCCESS) {
            throw new PaymentDomainException(PaymentErrorCode.CANNOT_REFUND_NON_SUCCESS_PAYMENT,
                "Only successful payments can be refunded. Current status: " + status);
        }
        transitionTo(PaymentStatus.REFUNDED);
        this.updatedAt = Instant.now();
        domainEvents.add(new PaymentRefundedEvent(id, orderId, userId, amount));
    }

    private void transitionTo(PaymentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot transition from " + status + " to " + target);
        }
        this.status = target;
    }

    public PaymentId getId() { return id; }
    public PaymentReference getPaymentReference() { return paymentReference; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public PaymentProvider getPaymentProvider() { return paymentProvider; }
    public String getTransactionId() { return transactionId; }
    public String getGatewayReference() { return gatewayReference; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getPaidAt() { return paidAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<DomainEvent> getDomainEvents() { return List.copyOf(domainEvents); }
    public void clearDomainEvents() { domainEvents.clear(); }
}
