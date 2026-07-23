package com.dsports.order.interfaces.order.dto;

import com.dsports.order.application.order.result.OrderSummaryResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryResponse(
    UUID id,
    String orderNumber,
    String status,
    int totalItems,
    BigDecimal grandTotal,
    String currency,
    Instant placedAt
) {
    public static OrderSummaryResponse from(OrderSummaryResult result) {
        return new OrderSummaryResponse(
            result.id(), result.orderNumber(), result.status(),
            result.totalItems(), result.grandTotal(), result.currency(),
            result.placedAt()
        );
    }
}
