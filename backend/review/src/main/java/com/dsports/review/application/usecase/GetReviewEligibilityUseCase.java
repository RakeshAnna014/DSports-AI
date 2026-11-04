package com.dsports.review.application.usecase;

import com.dsports.review.application.port.OrderQueryService;
import com.dsports.review.application.port.ReviewRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Tells a customer whether they can review a product and, if so, on the basis of which delivered order. */
public class GetReviewEligibilityUseCase {

    private final ReviewRepository reviewRepository;
    private final OrderQueryService orderQueryService;

    public GetReviewEligibilityUseCase(ReviewRepository reviewRepository, OrderQueryService orderQueryService) {
        this.reviewRepository = reviewRepository;
        this.orderQueryService = orderQueryService;
    }

    public enum Reason { ELIGIBLE, ALREADY_REVIEWED, NOT_PURCHASED_OR_NOT_DELIVERED }

    public record Eligibility(boolean eligible, Reason reason, UUID orderId, String existingReviewStatus) {}

    public Mono<Eligibility> execute(UUID productId, UUID userId) {
        return reviewRepository.findByProductIdAndUserId(productId, userId)
            .map(existing -> new Eligibility(false, Reason.ALREADY_REVIEWED, existing.getOrderId(),
                existing.getStatus().name()))
            .switchIfEmpty(Mono.defer(() -> orderQueryService.findReviewableOrder(userId, productId)
                .map(orderId -> new Eligibility(true, Reason.ELIGIBLE, orderId, null))
                .defaultIfEmpty(new Eligibility(false, Reason.NOT_PURCHASED_OR_NOT_DELIVERED, null, null))));
    }
}
