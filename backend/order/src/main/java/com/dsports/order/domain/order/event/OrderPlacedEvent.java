package com.dsports.order.domain.order.event;

import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.math.BigDecimal;
import java.util.UUID;

public class OrderPlacedEvent extends DomainEvent {
    private final OrderId orderId;
    private final OrderNumber orderNumber;
    private final UUID userId;
    private final BigDecimal grandTotal;
    private final String currency;

    public OrderPlacedEvent(OrderId orderId, OrderNumber orderNumber, UUID userId,
                            BigDecimal grandTotal, String currency) {
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.userId = userId;
        this.grandTotal = grandTotal;
        this.currency = currency;
    }

    public OrderId getOrderId() { return orderId; }
    public OrderNumber getOrderNumber() { return orderNumber; }
    public UUID getUserId() { return userId; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public String getCurrency() { return currency; }
}
