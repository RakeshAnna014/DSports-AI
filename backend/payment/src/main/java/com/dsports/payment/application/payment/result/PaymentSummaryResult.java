package com.dsports.payment.application.payment.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentSummaryResult(
    UUID id,
    String paymentReference,
    UUID orderId,
    BigDecimal amount,
    String currency,
    String paymentMethod,
    String status,
    Instant paidAt,
    Instant createdAt
) {}
