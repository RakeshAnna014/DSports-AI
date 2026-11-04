package com.dsports.review.domain.event;

import com.dsports.review.domain.model.ReviewId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public final class ReviewUpdatedEvent extends DomainEvent {

    private final ReviewId reviewId;
    private final UUID productId;
    private final UUID userId;

    public ReviewUpdatedEvent(ReviewId reviewId, UUID productId, UUID userId) {
        this.reviewId = reviewId;
        this.productId = productId;
        this.userId = userId;
    }

    public ReviewId getReviewId() { return reviewId; }
    public UUID getProductId() { return productId; }
    public UUID getUserId() { return userId; }
}
