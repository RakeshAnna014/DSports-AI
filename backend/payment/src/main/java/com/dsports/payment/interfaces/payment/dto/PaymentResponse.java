package com.dsports.payment.interfaces.payment.dto;

import com.dsports.payment.application.payment.result.PaymentResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Complete payment details")
public record PaymentResponse(
    @Schema(description = "Payment ID") UUID id,
    @Schema(description = "Unique payment reference") String paymentReference,
    @Schema(description = "Order ID") UUID orderId,
    @Schema(description = "Payment amount") BigDecimal amount,
    @Schema(description = "Currency code") String currency,
    @Schema(description = "Payment method") String paymentMethod,
    @Schema(description = "Payment provider") String paymentProvider,
    @Schema(description = "Gateway transaction ID") String transactionId,
    @Schema(description = "Payment status: CREATED, PENDING, PROCESSING, AUTHORIZED, SUCCESS (captured), PARTIALLY_REFUNDED, REFUNDED, FAILED, CANCELLED") String status,
    @Schema(description = "Failure reason if failed") String failureReason,
    @Schema(description = "When payment was captured") Instant paidAt,
    @Schema(description = "Total amount refunded so far") BigDecimal refundedAmount,
    @Schema(description = "Version for optimistic locking") int version,
    @Schema(description = "Created timestamp") Instant createdAt,
    @Schema(description = "Updated timestamp") Instant updatedAt
) {
    public static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(
            result.id(), result.paymentReference(), result.orderId(),
            result.amount(), result.currency(), result.paymentMethod(),
            result.paymentProvider(), result.transactionId(),
            result.status(), result.failureReason(), result.paidAt(), result.refundedAmount(),
            result.version(), result.createdAt(), result.updatedAt()
        );
    }
}
