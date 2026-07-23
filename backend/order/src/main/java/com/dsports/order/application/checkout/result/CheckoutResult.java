package com.dsports.order.application.checkout.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CheckoutResult(
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
    List<CheckoutItemResult> items
) {}
