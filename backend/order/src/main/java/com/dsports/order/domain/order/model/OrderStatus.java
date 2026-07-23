package com.dsports.order.domain.order.model;

public enum OrderStatus {
    CREATED,
    PENDING_PAYMENT,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED;

    public boolean isTerminal() {
        return this == DELIVERED || this == CANCELLED || this == REFUNDED;
    }

    public boolean isActive() {
        return this != CANCELLED && this != REFUNDED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING_PAYMENT || target == CANCELLED;
            case PENDING_PAYMENT -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == PROCESSING || target == CANCELLED;
            case PROCESSING -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED, CANCELLED, REFUNDED -> false;
        };
    }
}
