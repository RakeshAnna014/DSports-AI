package com.dsports.order.application.checkout.result;

import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutItem;

import java.util.List;

public final class CheckoutResultMapper {

    private CheckoutResultMapper() {}

    public static CheckoutResult toResult(Checkout checkout) {
        var delivery = checkout.getDeliveryMethod();
        return new CheckoutResult(
            checkout.getId().value(),
            checkout.getCustomerId(),
            checkout.getCartId(),
            checkout.getStatus().name(),
            checkout.getShippingAddressId(),
            delivery != null ? delivery.code() : null,
            delivery != null ? delivery.name() : null,
            checkout.getDeliveryCharge(),
            checkout.getDiscountAmount(),
            checkout.getSubtotal(),
            checkout.getTaxAmount(),
            checkout.getTotalAmount(),
            checkout.getCurrency(),
            checkout.getNotes(),
            checkout.getExpiresAt(),
            checkout.getValidatedAt(),
            checkout.getVersion(),
            checkout.getCreatedAt(),
            checkout.getUpdatedAt(),
            toItemResults(checkout.getItems())
        );
    }

    private static List<CheckoutItemResult> toItemResults(List<CheckoutItem> items) {
        return items.stream()
            .map(item -> new CheckoutItemResult(
                item.getId().value(),
                java.util.UUID.fromString(item.getProductId()),
                item.getProductName(),
                item.getSku(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getLineTotal(),
                item.getImageUrl(),
                item.getCreatedAt()
            ))
            .toList();
    }
}
