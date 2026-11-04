package com.dsports.review.infrastructure.persistence.mapper;

import com.dsports.review.domain.model.HelpfulVote;
import com.dsports.review.domain.model.Rating;
import com.dsports.review.domain.model.Review;
import com.dsports.review.domain.model.ReviewComment;
import com.dsports.review.domain.model.ReviewId;
import com.dsports.review.domain.model.ReviewStatus;
import com.dsports.review.infrastructure.persistence.entity.HelpfulVoteEntity;
import com.dsports.review.infrastructure.persistence.entity.ReviewEntity;

public final class ReviewEntityMapper {
    private ReviewEntityMapper() {}

    public static ReviewEntity toEntity(Review review) {
        var entity = new ReviewEntity();
        entity.setId(review.getId().value());
        entity.setProductId(review.getProductId());
        entity.setOrderId(review.getOrderId());
        entity.setUserId(review.getUserId());
        entity.setRating(review.getRating().value());
        entity.setTitle(review.getTitle());
        entity.setComment(review.getComment() != null ? review.getComment().text() : null);
        entity.setVerifiedPurchase(review.isVerifiedPurchase());
        entity.setHelpfulCount(review.getHelpfulCount());
        entity.setStatus(review.getStatus().name());
        entity.setCreatedAt(review.getCreatedAt());
        entity.setUpdatedAt(review.getUpdatedAt());
        entity.setVersion(review.getVersion());
        return entity;
    }

    public static Review toDomain(ReviewEntity entity) {
        return Review.reconstitute(
                ReviewId.fromUUID(entity.getId()),
                entity.getProductId(),
                entity.getOrderId(),
                entity.getUserId(),
                Rating.of(entity.getRating()),
                entity.getComment() != null ? ReviewComment.of(entity.getComment()) : null,
                entity.getTitle(),
                entity.isVerifiedPurchase(),
                entity.getHelpfulCount(),
                ReviewStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }

    public static HelpfulVoteEntity toHelpfulVoteEntity(HelpfulVote vote) {
        var entity = new HelpfulVoteEntity();
        entity.setId(vote.getId());
        entity.setReviewId(vote.getReviewId().value());
        entity.setUserId(vote.getUserId());
        entity.setNew(true);
        return entity;
    }

    public static HelpfulVote toHelpfulVoteDomain(HelpfulVoteEntity entity) {
        return new HelpfulVote(
                entity.getId(),
                ReviewId.fromUUID(entity.getReviewId()),
                entity.getUserId()
        );
    }
}
