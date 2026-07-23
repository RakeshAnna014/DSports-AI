package com.dsports.order.interfaces.checkout.dto;

import com.dsports.order.application.checkout.result.CheckoutResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutResponse(
    UUID id,
    UUID customerId,
    UUID cartId,
    String status,
    UUID shippingAddressId,
    String deliveryMethodCode,
    String deliveryMethodName,
    BigDecimal deliveryCharge,
    BigDecimal discountAmount,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal totalAmount,
    String currency,
    String notes,
    Instant expiresAt,
    Instant validatedAt,
    int version,
    Instant createdAt,
    Instant updatedAt,
    List<CheckoutItemResponse> items
) {
    public static CheckoutResponse from(CheckoutResult result) {
        return new CheckoutResponse(
            result.id(), result.customerId(), result.cartId(), result.status(),
            result.shippingAddressId(), result.deliveryMethodCode(),
            result.deliveryMethodName(), result.deliveryCharge(),
            result.discountAmount(), result.subtotal(), result.taxAmount(),
            result.totalAmount(), result.currency(), result.notes(),
            result.expiresAt(), result.validatedAt(), result.version(),
            result.createdAt(), result.updatedAt(),
            result.items().stream().map(CheckoutItemResponse::from).toList()
        );
    }
}
