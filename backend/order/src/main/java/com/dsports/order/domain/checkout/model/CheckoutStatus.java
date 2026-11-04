package com.dsports.order.domain.checkout.model;

public enum CheckoutStatus {
    PENDING,
    VALIDATED,
    READY_FOR_PAYMENT,
    EXPIRED,
    CANCELLED,
    COMPLETED;

    public boolean isTerminal() {
        return this == EXPIRED || this == CANCELLED || this == COMPLETED;
    }

    public boolean canTransitionTo(CheckoutStatus target) {
        return switch (this) {
            case PENDING -> target == VALIDATED || target == EXPIRED || target == CANCELLED;
            case VALIDATED -> target == READY_FOR_PAYMENT || target == COMPLETED || target == EXPIRED || target == CANCELLED;
            case READY_FOR_PAYMENT -> target == COMPLETED || target == EXPIRED || target == CANCELLED;
            case EXPIRED, CANCELLED, COMPLETED -> false;
        };
    }
}
