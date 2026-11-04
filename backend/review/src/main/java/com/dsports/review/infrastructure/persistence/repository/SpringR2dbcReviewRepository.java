package com.dsports.review.infrastructure.persistence.repository;

import com.dsports.review.infrastructure.persistence.entity.ReviewEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcReviewRepository extends ReactiveCrudRepository<ReviewEntity, UUID> {

    @Query("SELECT * FROM reviews WHERE product_id = :productId AND status = 'APPROVED' ORDER BY created_at DESC OFFSET :offset LIMIT :limit")
    Flux<ReviewEntity> findApprovedByProductIdPaged(UUID productId, int offset, int limit);

    Mono<ReviewEntity> findByProductIdAndUserId(UUID productId, UUID userId);

    Mono<Long> countByProductId(UUID productId);

    Flux<ReviewEntity> findByUserId(UUID userId);

    @Query("SELECT * FROM reviews WHERE status = :status ORDER BY created_at ASC OFFSET :offset LIMIT :limit")
    Flux<ReviewEntity> findByStatusPaged(String status, int offset, int limit);

    @Query("SELECT * FROM reviews ORDER BY created_at DESC OFFSET :offset LIMIT :limit")
    Flux<ReviewEntity> findAllPaged(int offset, int limit);

    Mono<Long> countByStatus(String status);

    Mono<Boolean> existsByProductIdAndUserId(UUID productId, UUID userId);
}
