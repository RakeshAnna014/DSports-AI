package com.dsports.payment.infrastructure.payment.persistence.repository;

import com.dsports.payment.infrastructure.payment.persistence.entity.PaymentEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Repository
public interface SpringR2dbcPaymentRepository extends R2dbcRepository<PaymentEntity, UUID> {
    Mono<PaymentEntity> findByPaymentReference(String paymentReference);
    Mono<PaymentEntity> findByOrderId(UUID orderId);
    Flux<PaymentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    Mono<Boolean> existsByOrderIdAndStatus(UUID orderId, String status);
}
