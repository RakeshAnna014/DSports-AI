package com.dsports.payment.domain.refund;

import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.PaymentId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A request to return (part of) a captured payment to the customer. */
public final class Refund {

    private final UUID id;
    private final PaymentId paymentId;
    private final UUID orderId;
    private final UUID customerId;
    private final BigDecimal amount;
    private final String currency;
    private final String reason;
    private final UUID requestedBy;
    private final String idempotencyKey;
    private RefundStatus status;
    private String gatewayReference;
    private String failureReason;
    private int attempts;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    private Refund(UUID id, PaymentId paymentId, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                   String reason, UUID requestedBy, String idempotencyKey, RefundStatus status,
                   String gatewayReference, String failureReason, int attempts, int version,
                   Instant createdAt, Instant updatedAt, Instant completedAt) {
        this.id = Objects.requireNonNull(id);
        this.paymentId = Objects.requireNonNull(paymentId);
        this.orderId = Objects.requireNonNull(orderId);
        this.customerId = Objects.requireNonNull(customerId);
        this.amount = Objects.requireNonNull(amount);
        this.currency = Objects.requireNonNull(currency);
        this.reason = reason;
        this.requestedBy = Objects.requireNonNull(requestedBy);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.status = Objects.requireNonNull(status);
        this.gatewayReference = gatewayReference;
        this.failureReason = failureReason;
        this.attempts = attempts;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.completedAt = completedAt;
    }

    public static Refund request(PaymentId paymentId, UUID orderId, UUID customerId, BigDecimal amount,
                                 String currency, String reason, UUID requestedBy, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_REFUND_AMOUNT, "Refund amount must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentDomainException(PaymentErrorCode.VALIDATION_ERROR, "Idempotency key is required");
        }
        var now = Instant.now();
        return new Refund(UUID.randomUUID(), paymentId, orderId, customerId, amount, currency, reason,
            requestedBy, idempotencyKey, RefundStatus.REQUESTED, null, null, 0, 0, now, now, null);
    }

    public static Refund reconstitute(UUID id, PaymentId paymentId, UUID orderId, UUID customerId, BigDecimal amount,
                                      String currency, String reason, UUID requestedBy, String idempotencyKey,
                                      RefundStatus status, String gatewayReference, String failureReason,
                                      int attempts, int version, Instant createdAt, Instant updatedAt,
                                      Instant completedAt) {
        return new Refund(id, paymentId, orderId, customerId, amount, currency, reason, requestedBy, idempotencyKey,
            status, gatewayReference, failureReason, attempts, version, createdAt, updatedAt, completedAt);
    }

    /**
     * Claims the refund for a gateway attempt. A PROCESSING refund whose outcome is unknown may be re-claimed:
     * the gateway call is idempotent on the refund ID, so it is never paid out twice.
     */
    public void startProcessing() {
        if (status != RefundStatus.PROCESSING) {
            transitionTo(RefundStatus.PROCESSING);
        }
        this.attempts++;
        this.failureReason = null;
        this.updatedAt = Instant.now();
    }

    public void complete(String gatewayReference) {
        transitionTo(RefundStatus.COMPLETED);
        this.gatewayReference = gatewayReference;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public void fail(String failureReason) {
        transitionTo(RefundStatus.FAILED);
        this.failureReason = failureReason;
        this.updatedAt = Instant.now();
    }

    public void reject(String rejectionReason) {
        transitionTo(RefundStatus.REJECTED);
        this.failureReason = rejectionReason;
        this.updatedAt = Instant.now();
    }

    private void transitionTo(RefundStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new PaymentDomainException(PaymentErrorCode.INVALID_REFUND_STATUS,
                "Refund " + id + " cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    public UUID getId() { return id; }
    public PaymentId getPaymentId() { return paymentId; }
    public UUID getOrderId() { return orderId; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getRequestedBy() { return requestedBy; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public RefundStatus getStatus() { return status; }
    public String getGatewayReference() { return gatewayReference; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
