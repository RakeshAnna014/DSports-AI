package com.dsports.pricing.domain.model;

import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;

import java.util.Objects;
import java.util.Set;

public record Currency(String code) {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "INR", "USD", "EUR", "GBP", "AUD", "CAD", "SGD", "AED"
    );

    public Currency {
        Objects.requireNonNull(code, "Currency code must not be null");
        var trimmed = code.trim().toUpperCase();
        if (trimmed.length() != 3) {
            throw new PricingDomainException(PricingErrorCode.INVALID_CURRENCY,
                    "Currency code must be a 3-letter ISO 4217 code: " + code);
        }
        if (!SUPPORTED_CURRENCIES.contains(trimmed)) {
            throw new PricingDomainException(PricingErrorCode.INVALID_CURRENCY,
                    "Unsupported currency: " + trimmed + ". Supported: " + SUPPORTED_CURRENCIES);
        }
    }

    public static Currency from(String code) {
        return new Currency(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
