package com.dsports.order.interfaces.order.dto;

import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.domain.order.model.AddressSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    String orderNumber,
    UUID userId,
    UUID checkoutId,
    String status,
    AddressSnapshot shippingAddress,
    AddressSnapshot billingAddress,
    List<OrderItemResponse> items,
    BigDecimal subtotal,
    BigDecimal shippingCharge,
    BigDecimal taxAmount,
    BigDecimal discountAmount,
    BigDecimal grandTotal,
    String currency,
    Instant placedAt,
    Instant cancelledAt,
    int version,
    Instant createdAt,
    Instant updatedAt
) {
    public static OrderResponse from(OrderResult result) {
        return new OrderResponse(
            result.id(), result.orderNumber(), result.userId(), result.checkoutId(),
            result.status(), result.shippingAddress(), result.billingAddress(),
            result.items().stream().map(OrderItemResponse::from).toList(),
            result.subtotal(), result.shippingCharge(), result.taxAmount(),
            result.discountAmount(), result.grandTotal(), result.currency(),
            result.placedAt(), result.cancelledAt(), result.version(),
            result.createdAt(), result.updatedAt()
        );
    }
}
