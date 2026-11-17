package com.dsports.cricket.domain.model;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

/** Validation helpers shared by the aggregates. Every failure is a {@code VALIDATION_ERROR} (a 400), never a 500. */
final class Rules {

    private Rules() {
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new CricketDomainException(CricketErrorCode.VALIDATION_ERROR, message);
        }
    }

    static String text(String value, int max, String label) {
        require(value != null && !value.isBlank() && value.trim().length() <= max, label + " is required (up to " + max + " characters)");
        return value.trim();
    }

    static String optionalText(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        require(value.trim().length() <= max, label + " is up to " + max + " characters");
        return value.trim();
    }
}
