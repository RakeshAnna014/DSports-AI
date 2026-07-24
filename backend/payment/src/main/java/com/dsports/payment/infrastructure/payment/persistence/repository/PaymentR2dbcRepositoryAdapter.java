package com.dsports.payment.infrastructure.payment.persistence.repository;

import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.domain.payment.model.Payment;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentReference;
import com.dsports.payment.infrastructure.payment.persistence.mapper.PaymentEntityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class PaymentR2dbcRepositoryAdapter implements PaymentRepository {
    private static final Logger log = LoggerFactory.getLogger(PaymentR2dbcRepositoryAdapter.class);

    private final SpringR2dbcPaymentRepository paymentRepo;
    private final TransactionalOperator transactionalOperator;

    public PaymentR2dbcRepositoryAdapter(SpringR2dbcPaymentRepository paymentRepo,
                                          ReactiveTransactionManager transactionManager) {
        this.paymentRepo = paymentRepo;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public Mono<Payment> findById(PaymentId id) {
        return paymentRepo.findById(id.value())
            .map(PaymentEntityMapper::toDomain);
    }

    @Override
    public Mono<Payment> findByPaymentReference(PaymentReference paymentReference) {
        return paymentRepo.findByPaymentReference(paymentReference.value())
            .map(PaymentEntityMapper::toDomain);
    }

    @Override
    public Mono<Payment> findByOrderId(UUID orderId) {
        return paymentRepo.findByOrderId(orderId)
            .map(PaymentEntityMapper::toDomain);
    }

    @Override
    public Flux<Payment> findByUserId(UUID userId) {
        return paymentRepo.findByUserIdOrderByCreatedAtDesc(userId)
            .map(PaymentEntityMapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByOrderIdAndStatus(UUID orderId, String status) {
        return paymentRepo.existsByOrderIdAndStatus(orderId, status);
    }

    @Override
    public Mono<Void> save(Payment payment) {
        var entity = PaymentEntityMapper.toEntity(payment);

        return transactionalOperator.execute(tx ->
            paymentRepo.findById(payment.getId().value())
                .flatMap(existing -> {
                    entity.setVersion(existing.getVersion());
                    return paymentRepo.save(entity);
                })
                .switchIfEmpty(paymentRepo.save(entity))
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                    new PaymentDomainException(PaymentErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "Concurrent modification detected for payment " + payment.getId().value()))
                .flatMap(savedEntity -> {
                    payment.setVersion(savedEntity.getVersion());
                    return Mono.empty();
                })
        ).then();
    }
}
