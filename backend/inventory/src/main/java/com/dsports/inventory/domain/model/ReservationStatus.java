package com.dsports.inventory.domain.model;

/**
 * Lifecycle of stock held for one order line.
 * <pre>
 * RESERVED ──fulfill()──► FULFILLED   (payment captured: units leave on-hand stock)
 *     └─────release()───► RELEASED    (order cancelled or expired: units become sellable again)
 * </pre>
 */
public enum ReservationStatus {
    RESERVED,
    FULFILLED,
    RELEASED;

    public boolean canTransitionTo(ReservationStatus target) {
        return this == RESERVED && (target == FULFILLED || target == RELEASED);
    }
}
