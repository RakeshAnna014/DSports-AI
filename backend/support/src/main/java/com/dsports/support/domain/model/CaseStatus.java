package com.dsports.support.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * <pre>
 * OPEN ──► INVESTIGATING ◄──► WAITING_FOR_CUSTOMER
 *   │          │  ▲  ◄──────► WAITING_FOR_EXTERNAL_PARTY
 *   │          ▼  │
 *   └───────► RESOLVED ──(reopen, only if the policy allows)──► INVESTIGATING
 * </pre>
 * Escalation is NOT a status: it is a separate attribute ({@link EscalationState}) because "a human should look at this" and
 * "a human has accepted this" are different facts, and neither changes where the case is in its lifecycle.
 */
public enum CaseStatus {
    OPEN,
    INVESTIGATING,
    WAITING_FOR_CUSTOMER,
    WAITING_FOR_EXTERNAL_PARTY,
    RESOLVED;

    private static final Set<CaseStatus> FROM_OPEN = EnumSet.of(INVESTIGATING, WAITING_FOR_CUSTOMER, RESOLVED);
    private static final Set<CaseStatus> FROM_INVESTIGATING = EnumSet.of(WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY, RESOLVED);
    private static final Set<CaseStatus> FROM_WAITING = EnumSet.of(INVESTIGATING, RESOLVED);
    private static final Set<CaseStatus> FROM_RESOLVED = EnumSet.of(INVESTIGATING);

    public boolean canTransitionTo(CaseStatus target) {
        return switch (this) {
            case OPEN -> FROM_OPEN.contains(target);
            case INVESTIGATING -> FROM_INVESTIGATING.contains(target);
            case WAITING_FOR_CUSTOMER, WAITING_FOR_EXTERNAL_PARTY -> FROM_WAITING.contains(target);
            case RESOLVED -> FROM_RESOLVED.contains(target);
        };
    }

    /** A case that still needs somebody's attention. */
    public boolean isUnresolved() {
        return this != RESOLVED;
    }
}
