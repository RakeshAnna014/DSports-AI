package com.dsports.payment.interfaces.payment.dto;

import com.dsports.payment.application.payment.result.PaymentSummaryResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Summary of a payment for history listing")
public record PaymentSummaryResponse(
    @Schema(description = "Payment ID") UUID id,
    @Schema(description = "Unique payment reference") String paymentReference,
    @Schema(description = "Order ID") UUID orderId,
    @Schema(description = "Payment amount") BigDecimal amount,
    @Schema(description = "Currency code") String currency,
    @Schema(description = "Payment method") String paymentMethod,
    @Schema(description = "Payment status") String status,
    @Schema(description = "When payment was completed") Instant paidAt,
    @Schema(description = "Created timestamp") Instant createdAt
) {
    public static PaymentSummaryResponse from(PaymentSummaryResult result) {
        return new PaymentSummaryResponse(
            result.id(), result.paymentReference(), result.orderId(),
            result.amount(), result.currency(), result.paymentMethod(),
            result.status(), result.paidAt(), result.createdAt()
        );
    }
}
