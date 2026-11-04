package com.dsports.order.domain.promotion.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A configurable offer. Only the backend decides whether it is valid and what it is worth; clients and the AI service
 * merely display the result. {@code demo} marks the synthetic offers seeded for demonstrations.
 */
public record Promotion(
    UUID id,
    String code,
    String title,
    String description,
    DiscountType discountType,
    BigDecimal discountValue,
    BigDecimal maxDiscountAmount,
    BigDecimal minOrderAmount,
    ScopeType scopeType,
    Set<UUID> scopeIds,
    Instant validFrom,
    Instant validUntil,
    Integer perCustomerLimit,
    Integer totalUsageLimit,
    boolean active,
    boolean demo
) {
    public enum DiscountType { PERCENT, FIXED }

    public enum ScopeType { ALL, CATEGORY, SPORT }

    public Promotion {
        scopeIds = scopeIds == null ? Set.of() : Set.copyOf(scopeIds);
    }
}
