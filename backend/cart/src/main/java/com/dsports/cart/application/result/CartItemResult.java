package com.dsports.cart.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CartItemResult(
    UUID id,
    UUID productId,
    String productName,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineTotal,
    Instant createdAt,
    Instant updatedAt
) {}
