package com.dsports.support.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcSupportCaseRepository extends R2dbcRepository<SupportCaseEntity, UUID> {

    Flux<SupportCaseEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Mono<SupportCaseEntity> findByCaseNumber(String caseNumber);

    Mono<SupportCaseEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);
}
