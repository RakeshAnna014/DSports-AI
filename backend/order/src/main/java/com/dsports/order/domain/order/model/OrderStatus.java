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

    /** Money has been captured for the order: it can only end through fulfilment or a refund, never a cancellation. */
    public boolean isPaid() {
        return this == CONFIRMED || this == PROCESSING || this == SHIPPED || this == DELIVERED;
    }

    public boolean isActive() {
        return this != CANCELLED && this != REFUNDED;
    }

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING_PAYMENT || target == CANCELLED;
            case PENDING_PAYMENT -> target == CONFIRMED || target == CANCELLED;
            case CONFIRMED -> target == PROCESSING || target == REFUNDED;
            case PROCESSING -> target == SHIPPED || target == REFUNDED;
            case SHIPPED -> target == DELIVERED || target == REFUNDED;
            case DELIVERED -> target == REFUNDED;
            case CANCELLED, REFUNDED -> false;
        };
    }
}
