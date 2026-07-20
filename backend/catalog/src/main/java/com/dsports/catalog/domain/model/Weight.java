package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.math.BigDecimal;

public record Weight(BigDecimal value, String unit) implements ValueObject {

    public Weight {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Weight must not be negative");
        }
        if (unit != null && !unit.isBlank() && !unit.matches("^(kg|g|lb|oz)$")) {
            throw new IllegalArgumentException("Invalid weight unit: " + unit);
        }
    }

    public static Weight from(BigDecimal value, String unit) {
        return new Weight(value, unit);
    }
}
