package com.dsports.review.application.result;

import com.dsports.review.domain.model.Review;

public final class ReviewResultMapper {
    private ReviewResultMapper() {}

    public static ReviewResult toResult(Review review) {
        return new ReviewResult(
            review.getId().value(),
            review.getProductId(),
            review.getOrderId(),
            review.getRating().value(),
            review.getTitle(),
            review.getComment() != null ? review.getComment().text() : null,
            review.isVerifiedPurchase(),
            review.getHelpfulCount(),
            review.getStatus().name(),
            review.getCreatedAt(),
            review.getUpdatedAt(),
            review.getVersion()
        );
    }
}
