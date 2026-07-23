package com.dsports.order.interfaces.checkout.dto;

import com.dsports.order.application.checkout.result.CheckoutItemResult;

import java.math.BigDecimal;
import java.util.UUID;

public record CheckoutItemResponse(
    UUID id,
    UUID productId,
    String productName,
    String sku,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal lineTotal,
    String imageUrl
) {
    public static CheckoutItemResponse from(CheckoutItemResult result) {
        return new CheckoutItemResponse(
            result.id(), result.productId(), result.productName(), result.sku(),
            result.quantity(), result.unitPrice(), result.lineTotal(), result.imageUrl()
        );
    }
}
