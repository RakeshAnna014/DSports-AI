package com.dsports.payment.domain.payment.model;

public enum PaymentStatus {
    CREATED,
    PENDING,
    PROCESSING,
    AUTHORIZED,
    SUCCESS,
    FAILED,
    CANCELLED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING || target == CANCELLED || target == FAILED;
            case PENDING -> target == PROCESSING || target == FAILED || target == CANCELLED;
            case PROCESSING -> target == AUTHORIZED || target == SUCCESS || target == FAILED || target == CANCELLED;
            case AUTHORIZED -> target == SUCCESS || target == FAILED;
            case SUCCESS -> target == REFUNDED;
            case FAILED, CANCELLED, REFUNDED -> false;
        };
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED || this == REFUNDED;
    }
}
