package com.dsports.review.application.usecase;

import com.dsports.review.application.command.ApproveReviewCommand;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.ReviewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class ApproveReviewUseCase {
    private static final Logger log = LoggerFactory.getLogger(ApproveReviewUseCase.class);

    private final ReviewRepository repository;
    private final EventPublisher eventPublisher;

    public ApproveReviewUseCase(ReviewRepository repository,
                                 EventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ReviewResult> execute(ApproveReviewCommand command) {
        var reviewId = ReviewId.fromUUID(command.reviewId());

        return repository.findById(reviewId)
            .switchIfEmpty(Mono.error(new ReviewDomainException(
                ReviewErrorCode.REVIEW_NOT_FOUND,
                "Review not found: " + reviewId
            )))
            .flatMap(review -> {
                if (command.approve()) {
                    review.approve();
                } else {
                    review.reject();
                }
                return repository.save(review)
                    .then(Mono.defer(() -> {
                        review.getDomainEvents().forEach(eventPublisher::publish);
                        review.clearDomainEvents();
                        log.info("Review {} {} by admin", review.getId(),
                            command.approve() ? "approved" : "rejected");
                        return Mono.just(ReviewResultMapper.toResult(review));
                    }));
            });
    }
}
