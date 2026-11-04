package com.dsports.support.domain.model;

/**
 * NONE      - nobody asked for a human.
 * REQUESTED - the assistant, the customer or a policy rule says a human should handle this. NO human has accepted it yet.
 * ACCEPTED  - a support staff member has actually taken the case (assigned it to themselves).
 */
public enum EscalationState {
    NONE, REQUESTED, ACCEPTED
}
