package com.dsports.returns.domain.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of the return policy for one product of one order. This is the authoritative answer; clients and the
 * shopping assistant explain it but never compute it.
 */
public record ReturnEligibility(
    boolean eligible,
    Reason reason,
    String message,
    UUID orderId,
    UUID productId,
    String productName,
    int orderedQuantity,
    int alreadyRequestedQuantity,
    int maxReturnableQuantity,
    BigDecimal unitPrice,
    String currency,
    Instant deliveredAt,
    Instant returnDeadline
) {

    public enum Reason {
        ELIGIBLE,
        ORDER_NOT_FOUND,
        ORDER_NOT_DELIVERED,
        PRODUCT_NOT_IN_ORDER,
        RETURN_WINDOW_EXPIRED,
        ALREADY_RETURNED
    }

    static ReturnEligibility rejected(Reason reason, String message, UUID orderId, UUID productId) {
        return new ReturnEligibility(false, reason, message, orderId, productId, null, 0, 0, 0, null, null, null, null);
    }
}
