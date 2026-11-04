package com.dsports.order.domain.promotion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Decides whether a promotion applies to a set of checkout lines and how much it is worth. Pure and deterministic.
 * Rules (all enforced here, never in a client):
 * <ul>
 *   <li>the offer must be active and inside its validity window;</li>
 *   <li>only lines in the offer's scope (all products, given categories or given sports) are eligible;</li>
 *   <li>the minimum order amount is compared with the eligible lines' subtotal;</li>
 *   <li>per-customer and overall usage limits (counts supplied by the caller, cancelled orders excluded);</li>
 *   <li>PERCENT: eligible subtotal x value / 100, rounded half-up to 2 places; FIXED: the value; both capped by the
 *       maximum discount (if any) and never more than the eligible subtotal, so a total can not become negative.</li>
 * </ul>
 * Stacking: one promotion per checkout (enforced by the caller). Tax is not recomputed here: the existing checkout rules
 * keep tax on the pre-discount subtotal and subtract the discount from the grand total.
 */
public final class PromotionCalculator {

    private PromotionCalculator() {}

    /** One checkout line with the catalogue attributes needed for scoping. */
    public record Line(UUID productId, BigDecimal lineTotal, UUID categoryId, UUID sportId) {}

    public enum Rejection {
        NOT_ACTIVE, NOT_STARTED, EXPIRED, NOT_APPLICABLE, MIN_ORDER_NOT_MET, CUSTOMER_LIMIT_REACHED, USAGE_LIMIT_REACHED
    }

    public record Evaluation(boolean eligible, BigDecimal discount, BigDecimal eligibleSubtotal, Rejection rejection, String reason) {
        static Evaluation rejected(Rejection rejection, BigDecimal eligibleSubtotal, String reason) {
            return new Evaluation(false, BigDecimal.ZERO.setScale(2), eligibleSubtotal, rejection, reason);
        }
    }

    public static Evaluation evaluate(Promotion promotion, List<Line> lines, Instant now, long customerRedemptions, long totalRedemptions) {
        var zero = BigDecimal.ZERO.setScale(2);
        if (!promotion.active()) {
            return Evaluation.rejected(Rejection.NOT_ACTIVE, zero, "This offer is not available.");
        }
        if (promotion.validFrom() != null && now.isBefore(promotion.validFrom())) {
            return Evaluation.rejected(Rejection.NOT_STARTED, zero, "This offer has not started yet.");
        }
        if (promotion.validUntil() != null && !now.isBefore(promotion.validUntil())) {
            return Evaluation.rejected(Rejection.EXPIRED, zero, "This offer has expired.");
        }
        var eligible = lines.stream().filter(line -> inScope(promotion, line))
            .map(Line::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (eligible.signum() <= 0) {
            return Evaluation.rejected(Rejection.NOT_APPLICABLE, eligible, "No item in your cart qualifies for this offer.");
        }
        if (eligible.compareTo(promotion.minOrderAmount()) < 0) {
            var missing = promotion.minOrderAmount().subtract(eligible).setScale(2, RoundingMode.HALF_UP);
            return Evaluation.rejected(Rejection.MIN_ORDER_NOT_MET, eligible,
                "Add INR " + missing.toPlainString() + " more of eligible items to use this offer (minimum INR "
                    + promotion.minOrderAmount().setScale(2, RoundingMode.HALF_UP).toPlainString() + ").");
        }
        if (promotion.perCustomerLimit() != null && customerRedemptions >= promotion.perCustomerLimit()) {
            return Evaluation.rejected(Rejection.CUSTOMER_LIMIT_REACHED, eligible, "You have already used this offer the maximum number of times.");
        }
        if (promotion.totalUsageLimit() != null && totalRedemptions >= promotion.totalUsageLimit()) {
            return Evaluation.rejected(Rejection.USAGE_LIMIT_REACHED, eligible, "This offer has reached its usage limit.");
        }
        var discount = switch (promotion.discountType()) {
            case PERCENT -> eligible.multiply(promotion.discountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case FIXED -> promotion.discountValue();
        };
        if (promotion.maxDiscountAmount() != null) discount = discount.min(promotion.maxDiscountAmount());
        discount = discount.min(eligible).setScale(2, RoundingMode.HALF_UP);
        if (discount.signum() <= 0) {
            return Evaluation.rejected(Rejection.NOT_APPLICABLE, eligible, "This offer gives no discount on your cart.");
        }
        return new Evaluation(true, discount, eligible, null, null);
    }

    private static boolean inScope(Promotion promotion, Line line) {
        return switch (promotion.scopeType()) {
            case ALL -> true;
            case CATEGORY -> line.categoryId() != null && promotion.scopeIds().contains(line.categoryId());
            case SPORT -> line.sportId() != null && promotion.scopeIds().contains(line.sportId());
        };
    }
}
