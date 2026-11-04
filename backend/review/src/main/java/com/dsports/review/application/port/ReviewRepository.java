package com.dsports.review.application.port;

import com.dsports.review.domain.model.HelpfulVote;
import com.dsports.review.domain.model.Review;
import com.dsports.review.domain.model.ReviewId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReviewRepository {
    Mono<Review> findById(ReviewId id);
    /** Publicly visible (APPROVED) reviews of a product. */
    Flux<Review> findApprovedByProductId(UUID productId, int page, int size);
    Mono<Review> findByProductIdAndUserId(UUID productId, UUID userId);
    Mono<Long> countByProductId(UUID productId);
    Flux<Review> findByUserId(UUID userId);
    Flux<Review> findAllPending(int page, int size);
    Mono<Long> countPending();
    Flux<Review> findAll(int page, int size);
    Mono<Long> countAll();
    Mono<Boolean> existsByProductIdAndUserId(UUID productId, UUID userId);
    Mono<Void> save(Review review);
    Mono<Void> saveHelpfulVote(HelpfulVote vote);
    Mono<Boolean> existsHelpfulVote(ReviewId reviewId, UUID userId);
}
