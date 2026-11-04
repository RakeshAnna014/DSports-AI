package com.dsports.support.domain.model;

/**
 * What a support executive decided. Recording an outcome does not move money or change an order: refunds, returns and
 * replacements happen only through their own flows (payment refund, return approval), which the executive uses separately.
 */
public enum ResolutionOutcome {
    ANSWERED,
    REFUND_PROCESSED_VIA_PAYMENT_FLOW,
    RETURN_APPROVED_VIA_RETURNS_FLOW,
    REPLACEMENT_ARRANGED,
    DELIVERY_CONFIRMED,
    NOT_ELIGIBLE,
    NO_ACTION_NEEDED,
    CUSTOMER_WITHDREW,
    DUPLICATE_OF_ANOTHER_CASE
}
