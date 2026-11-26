package com.dsports.ground.domain.model;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;

import java.math.BigDecimal;

/** Validation helpers shared by the venue and court aggregates. Every failure is a {@code VALIDATION_ERROR} (a 400), never a 500. */
final class Rules {

    private Rules() {
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, message);
        }
    }

    static void requireText(String value, int max, String label) {
        require(value != null && !value.isBlank() && value.trim().length() <= max, label + " is required (up to " + max + " characters)");
    }

    static void requireMoney(BigDecimal value, boolean required, String label) {
        if (value == null) {
            require(!required, label + " is required");
            return;
        }
        require(value.signum() >= 0 && value.scale() <= 2 && value.precision() - value.scale() <= 10,
            label + " must be a non-negative amount with at most 2 decimals");
    }
}
