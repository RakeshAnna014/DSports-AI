package com.dsports.order.application.order.result;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResult(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String productImage
) {}
