package com.dsports.order.interfaces.checkout.dto;

import com.dsports.order.application.checkout.result.CheckoutResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateCheckoutResponse(
    UUID id,
    UUID customerId,
    UUID cartId,
    String status,
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal deliveryCharge,
    BigDecimal discountAmount,
    BigDecimal totalAmount,
    String currency,
    Instant expiresAt,
    List<CheckoutItemResponse> items
) {
    public static CreateCheckoutResponse from(CheckoutResult result) {
        return new CreateCheckoutResponse(
            result.id(), result.customerId(), result.cartId(), result.status(),
            result.subtotal(), result.taxAmount(), result.deliveryCharge(),
            result.discountAmount(), result.totalAmount(), result.currency(),
            result.expiresAt(),
            result.items().stream().map(CheckoutItemResponse::from).toList()
        );
    }
}
