package com.dsports.order.domain.checkout.model;

public enum CheckoutStatus {
    PENDING,
    VALIDATED,
    READY_FOR_PAYMENT,
    EXPIRED,
    CANCELLED;

    public boolean isTerminal() {
        return this == EXPIRED || this == CANCELLED;
    }

    public boolean canTransitionTo(CheckoutStatus target) {
        return switch (this) {
            case PENDING -> target == VALIDATED || target == EXPIRED || target == CANCELLED;
            case VALIDATED -> target == READY_FOR_PAYMENT || target == EXPIRED || target == CANCELLED;
            case READY_FOR_PAYMENT -> target == EXPIRED || target == CANCELLED;
            case EXPIRED, CANCELLED -> false;
        };
    }
}
