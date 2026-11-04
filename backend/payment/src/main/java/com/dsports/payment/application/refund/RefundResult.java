package com.dsports.payment.application.refund;

import com.dsports.payment.domain.refund.Refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record RefundResult(
    UUID id,
    UUID paymentId,
    UUID orderId,
    BigDecimal amount,
    String currency,
    String reason,
    String status,
    String failureReason,
    int attempts,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt
) {
    public static RefundResult from(Refund refund) {
        return new RefundResult(refund.getId(), refund.getPaymentId().value(), refund.getOrderId(), refund.getAmount(),
            refund.getCurrency(), refund.getReason(), refund.getStatus().name(), refund.getFailureReason(),
            refund.getAttempts(), refund.getCreatedAt(), refund.getUpdatedAt(), refund.getCompletedAt());
    }
}
