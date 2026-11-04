package com.dsports.payment.domain.payment.model;

/**
 * Payment lifecycle. {@code SUCCESS} means the money has been captured.
 * <pre>
 * CREATED → PENDING → PROCESSING → AUTHORIZED ──beginCapture──► PROCESSING ──► SUCCESS ──► PARTIALLY_REFUNDED ──► REFUNDED
 *                         │              │                            │            └────────────────────────────► REFUNDED
 *                         └──► FAILED    ├──► CANCELLED (void)        └──► FAILED (capture declined)
 *                                        └──► FAILED
 * </pre>
 * A persisted {@code PROCESSING} payment always means a gateway call is in flight (or its outcome is unknown);
 * it blocks order cancellation and can be retried.
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    PROCESSING,
    AUTHORIZED,
    SUCCESS,
    PARTIALLY_REFUNDED,
    FAILED,
    CANCELLED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case CREATED -> target == PENDING || target == CANCELLED || target == FAILED;
            case PENDING -> target == PROCESSING || target == FAILED || target == CANCELLED;
            case PROCESSING -> target == AUTHORIZED || target == SUCCESS || target == FAILED || target == CANCELLED;
            case AUTHORIZED -> target == PROCESSING || target == SUCCESS || target == FAILED || target == CANCELLED;
            case SUCCESS -> target == PARTIALLY_REFUNDED || target == REFUNDED;
            case PARTIALLY_REFUNDED -> target == PARTIALLY_REFUNDED || target == REFUNDED;
            case FAILED, CANCELLED, REFUNDED -> false;
        };
    }

    /** No further state change is possible. */
    public boolean isTerminal() {
        return this == FAILED || this == CANCELLED || this == REFUNDED;
    }

    /** Started but not captured: blocks a second payment for the same order. */
    public boolean isOpen() {
        return this == CREATED || this == PENDING || this == PROCESSING || this == AUTHORIZED;
    }

    /** Money was taken from the customer (possibly refunded since). */
    public boolean isCaptured() {
        return this == SUCCESS || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
