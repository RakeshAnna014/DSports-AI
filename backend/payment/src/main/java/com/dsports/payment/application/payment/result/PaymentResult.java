package com.dsports.payment.application.payment.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResult(
    UUID id,
    String paymentReference,
    UUID orderId,
    UUID userId,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String paymentProvider,
    String transactionId,
    String gatewayReference,
    String status,
    String failureReason,
    Instant paidAt,
    int version,
    Instant createdAt,
    Instant updatedAt
) {}
