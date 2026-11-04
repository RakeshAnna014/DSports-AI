package com.dsports.returns.domain.policy;

import com.dsports.returns.domain.policy.ReturnEligibility.Reason;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The single place where return rules are decided:
 * <ol>
 *   <li>the order must exist and belong to the customer (callers pass {@code null} otherwise),</li>
 *   <li>the order must be DELIVERED,</li>
 *   <li>the product must be part of the order,</li>
 *   <li>the request must fall within {@code window} after delivery,</li>
 *   <li>units already requested for return (pending or approved) are not available again.</li>
 * </ol>
 */
public class ReturnPolicy {

    private final Duration window;

    public ReturnPolicy(Duration window) {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Return window must be positive");
        }
        this.window = window;
    }

    public Duration window() {
        return window;
    }

    public record OrderItemFacts(String productName, int quantity, BigDecimal unitPrice) {}

    /** What the policy needs to know about an order; delivered orders always carry a delivery time. */
    public record OrderFacts(UUID orderId, boolean delivered, String status, Instant deliveredAt, String currency,
                             Map<UUID, OrderItemFacts> items) {}

    public ReturnEligibility evaluate(UUID orderId, UUID productId, OrderFacts order, int alreadyRequested, Instant now) {
        if (order == null) {
            return ReturnEligibility.rejected(Reason.ORDER_NOT_FOUND, "Order not found.", orderId, productId);
        }
        if (!order.delivered()) {
            return ReturnEligibility.rejected(Reason.ORDER_NOT_DELIVERED,
                "Only delivered orders can be returned. This order is " + order.status() + ".", orderId, productId);
        }
        var item = order.items().get(productId);
        if (item == null) {
            return ReturnEligibility.rejected(Reason.PRODUCT_NOT_IN_ORDER, "This product is not part of the order.",
                orderId, productId);
        }
        var deadline = order.deliveredAt().plus(window);
        if (now.isAfter(deadline)) {
            return new ReturnEligibility(false, Reason.RETURN_WINDOW_EXPIRED,
                "The " + window.toDays() + "-day return window ended on " + deadline + ".", orderId, productId,
                item.productName(), item.quantity(), alreadyRequested, 0, item.unitPrice(), order.currency(),
                order.deliveredAt(), deadline);
        }
        var remaining = item.quantity() - alreadyRequested;
        if (remaining <= 0) {
            return new ReturnEligibility(false, Reason.ALREADY_RETURNED,
                "All units of this product have already been requested for return.", orderId, productId,
                item.productName(), item.quantity(), alreadyRequested, 0, item.unitPrice(), order.currency(),
                order.deliveredAt(), deadline);
        }
        return new ReturnEligibility(true, Reason.ELIGIBLE,
            "Up to " + remaining + " unit(s) can be returned until " + deadline + ".", orderId, productId,
            item.productName(), item.quantity(), alreadyRequested, remaining, item.unitPrice(), order.currency(),
            order.deliveredAt(), deadline);
    }
}
