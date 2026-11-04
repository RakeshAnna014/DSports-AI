package com.dsports.order.interfaces.checkout.dto;

import com.dsports.order.application.promotion.result.PromotionOfferResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class PromotionDtos {

    private PromotionDtos() {}

    public record ApplyPromotionRequest(@NotBlank @Size(max = 40) String code) {}

    @Schema(description = "A promotion as it applies to one checkout. discountAmount is the authoritative value for this cart; "
        + "demo=true marks synthetic demonstration offers.")
    public record PromotionOfferResponse(
        String code, String title, String description, String discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount, String scope, java.util.List<String> scopeNames, Instant validUntil, boolean eligible, BigDecimal discountAmount,
        BigDecimal eligibleSubtotal, String ineligibleReason, boolean applied, boolean demo) {

        public static PromotionOfferResponse from(PromotionOfferResult r) {
            return new PromotionOfferResponse(r.code(), r.title(), r.description(), r.discountType(), r.discountValue(), r.maxDiscountAmount(),
                r.minOrderAmount(), r.scope(), r.scopeNames(), r.validUntil(), r.eligible(), r.discountAmount(), r.eligibleSubtotal(), r.ineligibleReason(),
                r.applied(), r.demo());
        }
    }
}
