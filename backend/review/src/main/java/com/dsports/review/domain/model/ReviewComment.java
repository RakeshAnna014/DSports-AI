package com.dsports.review.domain.model;

import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;

public record ReviewComment(String text) {
    private static final int MAX_LENGTH = 5000;

    public ReviewComment {
        if (text == null || text.isBlank()) {
            throw new ReviewDomainException(
                ReviewErrorCode.INVALID_COMMENT,
                "Comment must not be null or blank"
            );
        }
        if (text.length() > MAX_LENGTH) {
            throw new ReviewDomainException(
                ReviewErrorCode.INVALID_COMMENT,
                "Comment must not exceed " + MAX_LENGTH + " characters, got: " + text.length()
            );
        }
    }

    public static ReviewComment of(String text) {
        return new ReviewComment(text);
    }
}
