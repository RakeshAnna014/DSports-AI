package com.dsports.payment.infrastructure.refund.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcRefundRepository extends R2dbcRepository<RefundEntity, UUID> {

    Flux<RefundEntity> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

    Mono<RefundEntity> findByPaymentIdAndIdempotencyKey(UUID paymentId, String idempotencyKey);

    Flux<RefundEntity> findAllByOrderByCreatedAtDesc();
}
