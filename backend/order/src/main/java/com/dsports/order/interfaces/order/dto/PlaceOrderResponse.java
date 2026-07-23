package com.dsports.order.interfaces.order.dto;

import com.dsports.order.application.order.result.OrderResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PlaceOrderResponse(
    UUID id,
    String orderNumber,
    String status,
    BigDecimal grandTotal,
    String currency,
    Instant placedAt
) {
    public static PlaceOrderResponse from(OrderResult result) {
        return new PlaceOrderResponse(
            result.id(), result.orderNumber(), result.status(),
            result.grandTotal(), result.currency(), result.placedAt()
        );
    }
}
