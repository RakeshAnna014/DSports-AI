package com.dsports.cart.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResult(
    UUID id,
    UUID userId,
    String status,
    int totalItems,
    BigDecimal totalAmount,
    int version,
    List<CartItemResult> items,
    Instant createdAt,
    Instant updatedAt
) {}
