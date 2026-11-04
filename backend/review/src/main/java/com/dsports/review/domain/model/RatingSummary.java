package com.dsports.review.domain.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record RatingSummary(int averageRating, int totalReviews, Map<Integer, Integer> distribution) {
    public RatingSummary {
        Objects.requireNonNull(distribution, "distribution must not be null");
        distribution = Collections.unmodifiableMap(distribution);
    }

    public static RatingSummary of(int averageRating, int totalReviews, Map<Integer, Integer> distribution) {
        return new RatingSummary(averageRating, totalReviews, distribution);
    }
}
