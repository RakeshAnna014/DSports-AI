package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

import java.math.BigDecimal;

public record Dimensions(BigDecimal length, BigDecimal width, BigDecimal height, String unit) implements ValueObject {

    public Dimensions {
        if (length != null && length.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Length must not be negative");
        }
        if (width != null && width.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Width must not be negative");
        }
        if (height != null && height.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Height must not be negative");
        }
        if (unit != null && !unit.isBlank() && !unit.matches("^(cm|m|in|ft)$")) {
            throw new IllegalArgumentException("Invalid dimension unit: " + unit);
        }
    }

    public static Dimensions from(BigDecimal length, BigDecimal width, BigDecimal height, String unit) {
        return new Dimensions(length, width, height, unit);
    }
}
