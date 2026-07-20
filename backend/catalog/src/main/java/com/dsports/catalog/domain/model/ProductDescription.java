package com.dsports.catalog.domain.model;

import com.dsports.shared.domain.kernel.ValueObject;

public record ProductDescription(String value) implements ValueObject {

    public static final int MAX_LENGTH = 2000;

    public ProductDescription {
        if (value != null && value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Description must not exceed " + MAX_LENGTH + " characters");
        }
    }

    public static ProductDescription from(String value) {
        return new ProductDescription(value != null ? value.trim() : null);
    }
}
