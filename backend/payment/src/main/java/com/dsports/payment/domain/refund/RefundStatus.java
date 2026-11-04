package com.dsports.payment.domain.refund;

/**
 * Refund lifecycle.
 * <pre>
 * REQUESTED ──approve──► PROCESSING ──gateway ok──► COMPLETED
 *     │                     │   ▲
 *     └──reject──► REJECTED  │   └──retry── FAILED ◄──gateway declined
 *                            └── stays PROCESSING when the gateway outcome is unknown (timeout); retry is allowed
 * </pre>
 * The payment's refunded amount changes only on COMPLETED, i.e. after the gateway confirmed the refund.
 */
public enum RefundStatus {
    REQUESTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REJECTED;

    public boolean canTransitionTo(RefundStatus target) {
        return switch (this) {
            case REQUESTED -> target == PROCESSING || target == REJECTED;
            case PROCESSING -> target == COMPLETED || target == FAILED;
            case FAILED -> target == PROCESSING;
            case COMPLETED, REJECTED -> false;
        };
    }

    /** Counts against the payment's refundable amount until it completes or is abandoned. */
    public boolean isPending() {
        return this == REQUESTED || this == PROCESSING || this == FAILED;
    }
}
