package com.dsports.review.infrastructure.persistence.repository;

import com.dsports.review.infrastructure.persistence.entity.HelpfulVoteEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcHelpfulVoteRepository extends ReactiveCrudRepository<HelpfulVoteEntity, UUID> {

    Mono<Boolean> existsByReviewIdAndUserId(UUID reviewId, UUID userId);
}
