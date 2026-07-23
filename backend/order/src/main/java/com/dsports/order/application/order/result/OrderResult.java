package com.dsports.order.application.order.result;

import com.dsports.order.domain.order.model.AddressSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResult(
    UUID id,
    String orderNumber,
    UUID userId,
    UUID checkoutId,
    String status,
    AddressSnapshot shippingAddress,
    AddressSnapshot billingAddress,
    List<OrderItemResult> items,
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
) {}
