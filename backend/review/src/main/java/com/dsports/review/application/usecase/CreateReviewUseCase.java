package com.dsports.review.application.usecase;

import com.dsports.review.application.command.CreateReviewCommand;
import com.dsports.review.application.port.EventPublisher;
import com.dsports.review.application.port.OrderQueryService;
import com.dsports.review.application.port.ReviewRepository;
import com.dsports.review.application.result.ReviewResult;
import com.dsports.review.application.result.ReviewResultMapper;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.review.domain.model.Rating;
import com.dsports.review.domain.model.Review;
import com.dsports.review.domain.model.ReviewComment;
import com.dsports.review.domain.model.ReviewId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class CreateReviewUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateReviewUseCase.class);

    private final ReviewRepository repository;
    private final OrderQueryService orderQueryService;
    private final EventPublisher eventPublisher;

    public CreateReviewUseCase(ReviewRepository repository,
                                OrderQueryService orderQueryService,
                                EventPublisher eventPublisher) {
        this.repository = repository;
        this.orderQueryService = orderQueryService;
        this.eventPublisher = eventPublisher;
    }

    public Mono<ReviewResult> execute(CreateReviewCommand command) {
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

        return orderQueryService.verifyPurchase(command.orderId(), command.userId(), command.productId())
            .flatMap(verification -> switch (verification) {
                case ELIGIBLE -> repository.existsByProductIdAndUserId(command.productId(), command.userId());
                case ORDER_NOT_FOUND -> Mono.<Boolean>error(new ReviewDomainException(ReviewErrorCode.ORDER_NOT_FOUND,
                    "Order " + command.orderId() + " not found"));
                case PRODUCT_NOT_IN_ORDER -> Mono.<Boolean>error(new ReviewDomainException(ReviewErrorCode.PRODUCT_NOT_PURCHASED,
                    "Product " + command.productId() + " is not part of order " + command.orderId()));
                case NOT_DELIVERED -> Mono.<Boolean>error(new ReviewDomainException(ReviewErrorCode.ORDER_NOT_DELIVERED,
                    "Order " + command.orderId() + " has not been delivered yet"));
            })
            .flatMap(alreadyReviewed -> {
                if (alreadyReviewed) {
                    return Mono.error(new ReviewDomainException(
                        ReviewErrorCode.DUPLICATE_REVIEW,
                        "You have already reviewed product " + command.productId()
                    ));
                }
                var reviewId = ReviewId.generate();
                var review = Review.create(
                    reviewId,
                    command.productId(),
                    command.orderId(),
                    command.userId(),
                    finalRating,
                    command.title(),
                    finalComment,
                    true
                );
                return repository.save(review)
                    .then(Mono.defer(() -> {
                        review.getDomainEvents().forEach(eventPublisher::publish);
                        review.clearDomainEvents();
                        log.info("Review {} created for product {} user {}",
                            review.getId(), review.getProductId(), review.getUserId());
                        return Mono.just(ReviewResultMapper.toResult(review));
                    }));
            });
    }
}
