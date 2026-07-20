package com.dsports.pricing.domain.model;

import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal value) {

    public static final int SCALE = 2;

    public Money {
        Objects.requireNonNull(value, "Money value must not be null");
        if (value.scale() > SCALE) {
            value = value.setScale(SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new PricingDomainException(PricingErrorCode.INVALID_PRICE,
                    "Money value must not be negative: " + value);
        }
    }

    public static Money from(double value) {
        return new Money(BigDecimal.valueOf(value));
    }

    public static Money from(BigDecimal value) {
        return new Money(value);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO.setScale(SCALE));
    }

    public boolean isGreaterThan(Money other) {
        return this.value.compareTo(other.value) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return this.value.compareTo(other.value) >= 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return this.value.compareTo(other.value) <= 0;
    }

    @Override
    public String toString() {
        return value.setScale(SCALE, RoundingMode.HALF_UP).toString();
    }
}
