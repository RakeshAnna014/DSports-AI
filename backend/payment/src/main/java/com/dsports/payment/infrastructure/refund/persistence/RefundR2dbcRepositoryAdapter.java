package com.dsports.payment.infrastructure.refund.persistence;

import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.refund.Refund;
import com.dsports.payment.domain.refund.RefundStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class RefundR2dbcRepositoryAdapter implements RefundRepository {

    private final SpringR2dbcRefundRepository springRepository;

    public RefundR2dbcRepositoryAdapter(SpringR2dbcRefundRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Mono<Refund> findById(UUID refundId) {
        return springRepository.findById(refundId).map(RefundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Refund> findByPaymentId(PaymentId paymentId) {
        return springRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId.value())
            .map(RefundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Refund> findByPaymentIdAndIdempotencyKey(PaymentId paymentId, String idempotencyKey) {
        return springRepository.findByPaymentIdAndIdempotencyKey(paymentId.value(), idempotencyKey)
            .map(RefundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Refund> findAll() {
        return springRepository.findAllByOrderByCreatedAtDesc().map(RefundR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Void> save(Refund refund) {
        return springRepository.save(toEntity(refund))
            .doOnNext(saved -> refund.setVersion(saved.getVersion()))
            .onErrorMap(OptimisticLockingFailureException.class, e ->
                new PaymentDomainException(PaymentErrorCode.REFUND_ALREADY_IN_PROGRESS,
                    "Refund " + refund.getId() + " is being processed by another request"))
            .onErrorMap(DataIntegrityViolationException.class, e ->
                new PaymentDomainException(PaymentErrorCode.DUPLICATE_PAYMENT,
                    "A refund with idempotency key " + refund.getIdempotencyKey() + " already exists"))
            .then();
    }

    private static RefundEntity toEntity(Refund refund) {
        var e = new RefundEntity();
        e.setId(refund.getId());
        e.setPaymentId(refund.getPaymentId().value());
        e.setOrderId(refund.getOrderId());
        e.setCustomerId(refund.getCustomerId());
        e.setAmount(refund.getAmount());
        e.setCurrency(refund.getCurrency());
        e.setReason(refund.getReason());
        e.setRequestedBy(refund.getRequestedBy());
        e.setIdempotencyKey(refund.getIdempotencyKey());
        e.setStatus(refund.getStatus().name());
        e.setGatewayReference(refund.getGatewayReference());
        e.setFailureReason(refund.getFailureReason());
        e.setAttempts(refund.getAttempts());
        e.setVersion(refund.getVersion());
        e.setCreatedAt(refund.getCreatedAt());
        e.setUpdatedAt(refund.getUpdatedAt());
        e.setCompletedAt(refund.getCompletedAt());
        return e;
    }

    private static Refund toDomain(RefundEntity e) {
        return Refund.reconstitute(e.getId(), PaymentId.fromUUID(e.getPaymentId()), e.getOrderId(), e.getCustomerId(),
            e.getAmount(), e.getCurrency(), e.getReason(), e.getRequestedBy(), e.getIdempotencyKey(),
            RefundStatus.valueOf(e.getStatus()), e.getGatewayReference(), e.getFailureReason(), e.getAttempts(),
            e.getVersion(), e.getCreatedAt(), e.getUpdatedAt(), e.getCompletedAt());
    }
}
