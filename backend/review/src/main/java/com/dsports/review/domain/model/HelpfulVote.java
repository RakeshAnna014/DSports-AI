package com.dsports.review.domain.model;

import java.util.Objects;
import java.util.UUID;

public final class HelpfulVote {

    private final UUID id;
    private final ReviewId reviewId;
    private final UUID userId;

    public HelpfulVote(UUID id, ReviewId reviewId, UUID userId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.reviewId = Objects.requireNonNull(reviewId, "reviewId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
    }

    public UUID getId() { return id; }
    public ReviewId getReviewId() { return reviewId; }
    public UUID getUserId() { return userId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HelpfulVote that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "HelpfulVote{id=" + id + ", reviewId=" + reviewId + ", userId=" + userId + "}";
    }
}
