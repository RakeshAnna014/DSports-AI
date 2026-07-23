package com.dsports.order.application.order.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryResult(
    UUID id,
    String orderNumber,
    String status,
    int totalItems,
    BigDecimal grandTotal,
    String currency,
    Instant placedAt
) {}
