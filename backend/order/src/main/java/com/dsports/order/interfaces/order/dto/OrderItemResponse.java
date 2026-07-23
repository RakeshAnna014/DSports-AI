package com.dsports.order.interfaces.order.dto;

import com.dsports.order.application.order.result.OrderItemResult;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String productImage
) {
    public static OrderItemResponse from(OrderItemResult result) {
        return new OrderItemResponse(
            result.id(), result.productId(), result.productName(),
            result.sku(), result.quantity(), result.unitPrice(),
            result.lineTotal(), result.productImage()
        );
    }
}
