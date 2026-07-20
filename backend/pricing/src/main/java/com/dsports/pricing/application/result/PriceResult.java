package com.dsports.pricing.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PriceResult(
    UUID id,
    UUID productId,
    BigDecimal mrp,
    BigDecimal sellingPrice,
    String currency,
    Instant effectiveFrom,
    Instant effectiveTo,
    String status,
    int version,
    Instant createdAt,
    Instant updatedAt
) {}
