package com.dsports.order.domain.order.event;

import com.dsports.order.domain.order.model.OrderId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public class OrderDeliveredEvent extends DomainEvent {
    private final OrderId orderId;
    private final UUID userId;

    public OrderDeliveredEvent(OrderId orderId, UUID userId) {
        this.orderId = orderId;
        this.userId = userId;
    }

    public OrderId getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
}
