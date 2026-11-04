package com.dsports.returns.domain.model;

import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A customer's request to send back some units of one product from a delivered order. */
public final class ReturnRequest {

    private final UUID id;
    private final UUID orderId;
    private final UUID userId;
    private final UUID productId;
    private final String productName;
    private final int quantity;
    private final String reason;
    private final String idempotencyKey;
    private ReturnStatus status;
    private String decisionNote;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private ReturnRequest(UUID id, UUID orderId, UUID userId, UUID productId, String productName, int quantity,
                          String reason, String idempotencyKey, ReturnStatus status, String decisionNote,
                          int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.orderId = Objects.requireNonNull(orderId);
        this.userId = Objects.requireNonNull(userId);
        this.productId = Objects.requireNonNull(productId);
        this.productName = productName;
        this.quantity = quantity;
        this.reason = reason;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.status = Objects.requireNonNull(status);
        this.decisionNote = decisionNote;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ReturnRequest create(UUID orderId, UUID userId, UUID productId, String productName, int quantity,
                                       String reason, String idempotencyKey) {
        if (quantity <= 0) {
            throw new ReturnsDomainException(ReturnsErrorCode.VALIDATION_ERROR, "Quantity must be positive");
        }
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ReturnsDomainException(ReturnsErrorCode.VALIDATION_ERROR,
                    "A reason of 1-500 characters is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ReturnsDomainException(ReturnsErrorCode.VALIDATION_ERROR,
                    "An Idempotency-Key of 1-100 characters is required");
        }
        var now = Instant.now();
        return new ReturnRequest(UUID.randomUUID(), orderId, userId, productId, productName, quantity, reason.trim(),
                idempotencyKey, ReturnStatus.REQUESTED, null, 0, now, now);
    }

    public static ReturnRequest reconstitute(UUID id, UUID orderId, UUID userId, UUID productId, String productName,
                                             int quantity, String reason, String idempotencyKey, ReturnStatus status,
                                             String decisionNote, int version, Instant createdAt, Instant updatedAt) {
        return new ReturnRequest(id, orderId, userId, productId, productName, quantity, reason, idempotencyKey,
                status, decisionNote, version, createdAt, updatedAt);
    }

    public void approve(String note) {
        transitionTo(ReturnStatus.APPROVED, note);
    }

    public void reject(String note) {
        transitionTo(ReturnStatus.REJECTED, note);
    }

    private void transitionTo(ReturnStatus target, String note) {
        if (!status.canTransitionTo(target)) {
            throw new ReturnsDomainException(ReturnsErrorCode.INVALID_STATUS_TRANSITION,
                    "Return " + id + " is " + status + " and cannot become " + target);
        }
        this.status = target;
        this.decisionNote = note;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public String getReason() { return reason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public ReturnStatus getStatus() { return status; }
    public String getDecisionNote() { return decisionNote; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
