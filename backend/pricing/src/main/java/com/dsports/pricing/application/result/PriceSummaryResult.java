package com.dsports.pricing.application.result;

import java.math.BigDecimal;
import java.util.UUID;

public record PriceSummaryResult(
    UUID id,
    UUID productId,
    BigDecimal mrp,
    BigDecimal sellingPrice,
    String currency,
    String status
) {}
