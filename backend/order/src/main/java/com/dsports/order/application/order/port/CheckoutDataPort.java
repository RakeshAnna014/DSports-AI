package com.dsports.order.application.order.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface CheckoutDataPort {
    Mono<CheckoutData> getValidatedCheckout(UUID checkoutId, UUID userId);

    record CheckoutData(
        UUID checkoutId,
        UUID cartId,
        UUID shippingAddressId,
        UUID billingAddressId,
        List<CheckoutItemData> items,
        BigDecimal subtotal,
        BigDecimal shippingCharge,
        BigDecimal taxAmount,
        BigDecimal discountAmount,
        BigDecimal totalAmount,
        String currency,
        AddressData shippingAddress,
        AddressData billingAddress
    ) {}

    record CheckoutItemData(
        UUID productId,
        String productName,
        String sku,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String productImage
    ) {}

    record AddressData(
        String line1,
        String line2,
        String city,
        String state,
        String country,
        String postalCode,
        String fullName,
        String phone
    ) {}
}
