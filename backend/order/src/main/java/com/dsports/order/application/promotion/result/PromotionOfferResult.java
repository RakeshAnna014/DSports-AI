package com.dsports.order.application.promotion.result;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One promotion as it applies to a particular checkout. {@code discountAmount} is the authoritative value for this cart
 * (zero when not eligible, with the reason). {@code demo} marks synthetic offers.
 */
public record PromotionOfferResult(
    String code,
    String title,
    String description,
    String discountType,
    BigDecimal discountValue,
    BigDecimal maxDiscountAmount,
    BigDecimal minOrderAmount,
    String scope,
    java.util.List<String> scopeNames,
    Instant validUntil,
    boolean eligible,
    BigDecimal discountAmount,
    BigDecimal eligibleSubtotal,
    String ineligibleReason,
    boolean applied,
    boolean demo
) {}
