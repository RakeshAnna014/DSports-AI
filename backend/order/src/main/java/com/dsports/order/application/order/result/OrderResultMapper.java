package com.dsports.order.application.order.result;

import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderItem;

import java.util.List;

public final class OrderResultMapper {

    private OrderResultMapper() {}

    public static OrderResult toResult(Order order) {
        return new OrderResult(
            order.getId().value(),
            order.getOrderNumber().value(),
            order.getUserId(),
            order.getCheckoutId(),
            order.getStatus().name(),
            order.getShippingAddressSnapshot(),
            order.getBillingAddressSnapshot(),
            order.getItems().stream()
                .map(OrderResultMapper::toItemResult)
                .toList(),
            order.getSubtotal(),
            order.getShippingCharge(),
            order.getTaxAmount(),
            order.getDiscountAmount(),
            order.getGrandTotal(),
            order.getCurrency(),
            order.getPlacedAt(),
            order.getCancelledAt(),
            order.getVersion(),
            order.getCreatedAt(),
            order.getUpdatedAt()
        );
    }

    public static OrderItemResult toItemResult(OrderItem item) {
        return new OrderItemResult(
            item.getId().value(),
            item.getProductId(),
            item.getProductName(),
            item.getSku(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getLineTotal(),
            item.getProductImage()
        );
    }

    public static OrderSummaryResult toSummary(Order order) {
        return new OrderSummaryResult(
            order.getId().value(),
            order.getOrderNumber().value(),
            order.getStatus().name(),
            order.getItems().size(),
            order.getGrandTotal(),
            order.getCurrency(),
            order.getPlacedAt()
        );
    }

    public static List<OrderSummaryResult> toSummaryList(List<Order> orders) {
        return orders.stream()
            .map(OrderResultMapper::toSummary)
            .toList();
    }
}
