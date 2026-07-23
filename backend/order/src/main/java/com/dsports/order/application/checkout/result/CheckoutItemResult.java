package com.dsports.order.application.checkout.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CheckoutItemResult(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String imageUrl,
    Instant createdAt
) {}
