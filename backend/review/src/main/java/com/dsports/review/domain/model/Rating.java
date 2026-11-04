package com.dsports.review.domain.model;

import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;

public record Rating(int value) {
    public Rating {
        if (value < 1 || value > 5) {
            throw new ReviewDomainException(
                ReviewErrorCode.INVALID_RATING,
                "Rating must be between 1 and 5, got: " + value
            );
        }
    }

    public static Rating of(int value) {
        return new Rating(value);
    }
}
