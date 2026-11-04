package com.dsports.review.application.usecase;

import com.dsports.review.application.command.UpdateReviewCommand;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.Rating;
import com.dsports.review.domain.model.ReviewComment;
import com.dsports.review.domain.model.ReviewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class UpdateReviewUseCase {
    private static final Logger log = LoggerFactory.getLogger(UpdateReviewUseCase.class);

    private final ReviewRepository repository;
    private final EventPublisher eventPublisher;

    public UpdateReviewUseCase(ReviewRepository repository,
                                EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ReviewResult> execute(UpdateReviewCommand command) {
        var reviewId = ReviewId.fromUUID(command.reviewId());

        Rating rating;
        try {
            rating = Rating.of(command.rating());
        } catch (ReviewDomainException e) {
            return Mono.error(e);
        }

        ReviewComment comment = null;
        if (command.comment() != null && !command.comment().isBlank()) {
            try {
                comment = ReviewComment.of(command.comment());
            } catch (ReviewDomainException e) {
                return Mono.error(e);
            }
        }

        var finalRating = rating;
        var finalComment = comment;

        return repository.findById(reviewId)
            .switchIfEmpty(Mono.error(new ReviewDomainException(
                ReviewErrorCode.REVIEW_NOT_FOUND,
                "Review not found: " + reviewId
            )))
            .flatMap(review -> {
                if (!review.getUserId().equals(command.userId())) {
                    return Mono.error(new ReviewDomainException(
                        ReviewErrorCode.REVIEW_NOT_OWNED_BY_USER,
                        "Review " + reviewId + " does not belong to user " + command.userId()
                    ));
                }
                review.edit(finalRating, command.title(), finalComment);
                return repository.save(review)
                    .then(Mono.defer(() -> {
                        review.getDomainEvents().forEach(eventPublisher::publish);
                        review.clearDomainEvents();
                        log.info("Review {} updated by user {}", review.getId(), review.getUserId());
                        return Mono.just(ReviewResultMapper.toResult(review));
                    }));
            });
    }
}
