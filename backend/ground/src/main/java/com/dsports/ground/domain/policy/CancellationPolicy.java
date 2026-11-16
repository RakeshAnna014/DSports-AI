package com.dsports.ground.domain.policy;

import com.dsports.ground.domain.model.CancelledBy;

import java.time.Duration;
import java.time.Instant;

/**
 * Refund share for a cancelled, paid booking. More than {@code fullRefundBefore} ahead: 100%. From {@code partialRefundBefore}
 * up to and including {@code fullRefundBefore} ahead: {@code partialPercent}%. Closer than {@code partialRefundBefore}: nothing.
 * A cancellation by the ground owner is always refunded in full, whatever the timing. (The numbers are configuration, not law.)
 */
public record CancellationPolicy(Duration fullRefundBefore, Duration partialRefundBefore, int partialPercent) {

    public static CancellationPolicy defaults() {
        return new CancellationPolicy(Duration.ofHours(24), Duration.ofHours(6), 50);
    }

    public int refundPercent(Instant bookingStart, Instant now, CancelledBy by) {
        if (by == CancelledBy.OWNER) {
            return 100;
        }
        var untilStart = Duration.between(now, bookingStart);
        if (untilStart.compareTo(fullRefundBefore) > 0) {
            return 100;
        }
        if (untilStart.compareTo(partialRefundBefore) >= 0) {
            return partialPercent;
        }
        return 0;
    }
}
