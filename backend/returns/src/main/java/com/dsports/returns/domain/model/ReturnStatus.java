package com.dsports.returns.domain.model;

/**
 * <pre>
 * REQUESTED ──approve──► APPROVED
 *     └─────reject─────► REJECTED
 * </pre>
 * Approval records the decision to accept the goods back; the money is returned through the existing admin refund flow.
 */
public enum ReturnStatus {
    REQUESTED,
    APPROVED,
    REJECTED;

    public boolean canTransitionTo(ReturnStatus target) {
        return this == REQUESTED && (target == APPROVED || target == REJECTED);
    }

    /** Units of such a return count against the quantity that can still be returned. */
    public boolean holdsQuantity() {
        return this == REQUESTED || this == APPROVED;
    }
}
