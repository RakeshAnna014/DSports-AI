package com.dsports.returns.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcReturnRepository extends R2dbcRepository<ReturnRequestEntity, UUID> {

    Flux<ReturnRequestEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Flux<ReturnRequestEntity> findAllByOrderByCreatedAtDesc();

    Mono<ReturnRequestEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
