package com.dsports.pricing.domain.model;

import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;

import java.time.Instant;
import java.util.Objects;

public record EffectiveDate(Instant effectiveFrom, Instant effectiveTo) {

    public EffectiveDate {
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
            throw new PricingDomainException(PricingErrorCode.INVALID_EFFECTIVE_DATE,
                    "effectiveTo must be after effectiveFrom");
        }
    }

    public static EffectiveDate immediate() {
        return new EffectiveDate(Instant.now(), null);
    }

    public static EffectiveDate from(Instant effectiveFrom) {
        return new EffectiveDate(effectiveFrom, null);
    }

    public static EffectiveDate from(Instant effectiveFrom, Instant effectiveTo) {
        return new EffectiveDate(effectiveFrom, effectiveTo);
    }

    public boolean isEffectiveNow() {
        var now = Instant.now();
        return !now.isBefore(effectiveFrom) && (effectiveTo == null || !now.isAfter(effectiveTo));
    }

    public boolean isInFuture() {
        return effectiveFrom.isAfter(Instant.now());
    }
}
