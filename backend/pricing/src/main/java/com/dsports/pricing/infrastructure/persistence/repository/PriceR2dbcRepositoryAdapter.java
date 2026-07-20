package com.dsports.pricing.infrastructure.persistence.repository;

import com.dsports.pricing.application.port.EventPublisher;
import com.dsports.pricing.application.port.PriceRepository;
import com.dsports.pricing.domain.exception.PricingDomainException;
import com.dsports.pricing.domain.exception.PricingErrorCode;
import com.dsports.pricing.domain.model.*;
import com.dsports.pricing.infrastructure.persistence.entity.PriceEntity;
import com.dsports.pricing.infrastructure.persistence.mapper.PriceEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class PriceR2dbcRepositoryAdapter implements PriceRepository {

    private final SpringR2dbcPriceRepository springRepository;
    private final PriceEntityMapper mapper;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator rxtx;

    public PriceR2dbcRepositoryAdapter(SpringR2dbcPriceRepository springRepository,
                                        PriceEntityMapper mapper,
                                        EventPublisher eventPublisher,
                                        TransactionalOperator rxtx) {
        this.springRepository = springRepository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.rxtx = rxtx;
    }

    @Override
    public Mono<Price> findById(PriceId id) {
        return springRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Flux<Price> findByProductId(ProductId productId) {
        return springRepository.findByProductIdOrderByCreatedAtDesc(productId.value())
                .map(mapper::toDomain);
    }

    @Override
    public Flux<Price> findAll() {
        return springRepository.findAll()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByProductIdAndCurrencyAndStatus(ProductId productId, Currency currency, PriceStatus status) {
        return springRepository.existsByProductIdAndCurrencyAndStatus(
                productId.value(), currency.code(), status.name());
    }

    @Override
    public Mono<Void> deactivateActivePrices(ProductId productId, Currency currency, PriceId excludeId) {
        return springRepository.deactivateActivePrices(
                productId.value(), currency.code(), excludeId.value()).then();
    }

    @Override
    public Mono<Void> save(Price price) {
        var entity = mapper.toEntity(price);
        return rxtx.execute(reactiveTransaction ->
            springRepository.save(entity)
                    .onErrorMap(OptimisticLockingFailureException.class, e ->
                            new PricingDomainException(PricingErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                    "Price was modified by another request. Please retry."))
                    .onErrorMap(DataIntegrityViolationException.class, e -> {
                        var msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (msg.contains("uq_price_product_currency_active")) {
                            return new PricingDomainException(PricingErrorCode.OVERLAPPING_ACTIVE_PRICE,
                                    "An active price already exists for this product and currency");
                        }
                        return new PricingDomainException(PricingErrorCode.GENERIC,
                                "Data integrity violation: " + e.getMessage());
                    })
                    .then(Mono.defer(() -> {
                        var events = price.getDomainEvents();
                        price.clearDomainEvents();
                        return Flux.fromIterable(events)
                                .flatMap(event -> Mono.fromRunnable(() -> eventPublisher.publish(event))
                                        .subscribeOn(Schedulers.boundedElastic()))
                                .then();
                    }))
        ).then();
    }
}
