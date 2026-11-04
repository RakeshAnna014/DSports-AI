package com.dsports.review.domain.model;

import java.util.UUID;

public record ReviewId(UUID value) {
    public ReviewId {
        if (value == null) {
            throw new IllegalArgumentException("ReviewId must not be null");
        }
    }

    public static ReviewId generate() {
        return new ReviewId(UUID.randomUUID());
    }

    public static ReviewId fromUUID(UUID value) {
        return new ReviewId(value);
    }
}
