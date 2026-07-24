package com.dsports.payment.infrastructure.payment.persistence.repository;

import com.dsports.payment.infrastructure.payment.persistence.entity.PaymentAuditEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface SpringR2dbcPaymentAuditRepository extends R2dbcRepository<PaymentAuditEntity, UUID> {
    Flux<PaymentAuditEntity> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);
}
