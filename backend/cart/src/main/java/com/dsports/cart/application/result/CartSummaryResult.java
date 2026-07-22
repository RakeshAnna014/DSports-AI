package com.dsports.cart.application.result;

import java.math.BigDecimal;
import java.util.UUID;

public record CartSummaryResult(
    UUID id,
    UUID userId,
    String status,
    int totalItems,
    BigDecimal totalAmount,
    int version
) {}
