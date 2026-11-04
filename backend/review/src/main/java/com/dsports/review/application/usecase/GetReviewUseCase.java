package com.dsports.review.application.usecase;

import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.query.GetReviewQuery;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.ReviewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class GetReviewUseCase {
    private static final Logger log = LoggerFactory.getLogger(GetReviewUseCase.class);

    private final ReviewRepository repository;

    public GetReviewUseCase(ReviewRepository repository) {
        this.repository = repository;
    }

    public Mono<ReviewResult> execute(GetReviewQuery query) {
        var reviewId = ReviewId.fromUUID(query.reviewId());

        return repository.findById(reviewId)
            .switchIfEmpty(Mono.error(new ReviewDomainException(
                ReviewErrorCode.REVIEW_NOT_FOUND,
                "Review not found: " + query.reviewId()
            )))
            .flatMap(review -> {
                if (!query.isAdmin() && !review.getUserId().equals(query.userId())) {
                    return Mono.error(new ReviewDomainException(
                        ReviewErrorCode.REVIEW_NOT_OWNED_BY_USER,
                        "Review does not belong to user"
                    ));
                }
                return Mono.just(ReviewResultMapper.toResult(review));
            });
    }
}
