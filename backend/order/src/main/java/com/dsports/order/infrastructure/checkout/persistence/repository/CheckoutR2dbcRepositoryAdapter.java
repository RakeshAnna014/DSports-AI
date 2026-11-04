package com.dsports.order.infrastructure.checkout.persistence.repository;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.infrastructure.checkout.persistence.mapper.CheckoutEntityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
public class CheckoutR2dbcRepositoryAdapter implements CheckoutRepository {
    private static final Logger log = LoggerFactory.getLogger(CheckoutR2dbcRepositoryAdapter.class);

    private final SpringR2dbcCheckoutRepository checkoutRepo;
    private final SpringR2dbcCheckoutItemRepository itemRepo;
    private final TransactionalOperator transactionalOperator;

    public CheckoutR2dbcRepositoryAdapter(SpringR2dbcCheckoutRepository checkoutRepo,
                                          SpringR2dbcCheckoutItemRepository itemRepo,
                                          ReactiveTransactionManager transactionManager) {
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
        this.checkoutRepo = checkoutRepo;
        this.itemRepo = itemRepo;
    }

    @Override
    public Mono<Checkout> findById(CheckoutId id) {
        return checkoutRepo.findById(id.value())
            .flatMap(entity -> itemRepo.findByCheckoutId(id.value())
                .collectList()
                .map(items -> CheckoutEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Mono<Checkout> findByCustomerId(UUID customerId) {
        return checkoutRepo.findActiveByCustomerId(customerId)
            .flatMap(entity -> itemRepo.findByCheckoutId(entity.getId())
                .collectList()
                .map(items -> CheckoutEntityMapper.toDomain(entity, items)));
    }

    @Override
    public Mono<Boolean> existsActiveCheckout(UUID customerId) {
        return checkoutRepo.existsActiveCheckout(customerId);
    }

    @Override
    public Mono<Void> save(Checkout checkout) {
        var entity = CheckoutEntityMapper.toEntity(checkout);
        var itemEntities = checkout.getItems().stream()
            .map(CheckoutEntityMapper::toItemEntity)
            .toList();

        return transactionalOperator.execute(tx -> checkoutRepo.save(entity)
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                    new CheckoutDomainException(CheckoutErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                        "Checkout " + checkout.getId().value() + " was modified concurrently. Reload and retry."))
                .doOnNext(saved -> checkout.setVersion(saved.getVersion()))
                .then(itemRepo.deleteByCheckoutId(checkout.getId().value()))
                .thenMany(itemRepo.saveAll(itemEntities)))
            .then()
            .doOnSuccess(v -> log.debug("Checkout {} saved successfully", checkout.getId().value()));
    }

    @Override
    public Mono<Void> delete(CheckoutId id) {
        return itemRepo.deleteByCheckoutId(id.value())
            .then(checkoutRepo.deleteById(id.value()));
    }
}
