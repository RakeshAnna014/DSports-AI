package com.dsports.inventory.infrastructure.persistence.repository;

import com.dsports.inventory.application.port.EventPublisher;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.*;
import com.dsports.inventory.infrastructure.persistence.entity.InventoryEntity;
import com.dsports.inventory.infrastructure.persistence.mapper.InventoryEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class InventoryR2dbcRepositoryAdapter implements InventoryRepository {

    private final SpringR2dbcInventoryRepository springRepository;
    private final InventoryEntityMapper mapper;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator rxtx;

    public InventoryR2dbcRepositoryAdapter(SpringR2dbcInventoryRepository springRepository,
                                           InventoryEntityMapper mapper,
                                           EventPublisher eventPublisher,
                                           TransactionalOperator rxtx) {
        this.springRepository = springRepository;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.rxtx = rxtx;
    }

    @Override
    public Mono<InventoryItem> findById(InventoryId id) {
        return springRepository.findById(id.value())
                .map(mapper::toDomain);
    }

    @Override
    public Mono<InventoryItem> findByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId) {
        return springRepository.findByProductIdAndWarehouseId(productId.value(), warehouseId.value())
                .map(mapper::toDomain);
    }

    @Override
    public Flux<InventoryItem> findByProductId(ProductId productId) {
        return springRepository.findByProductId(productId.value())
                .map(mapper::toDomain);
    }

    @Override
    public Flux<InventoryItem> findAll() {
        return springRepository.findAll()
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> existsByProductIdAndWarehouseId(ProductId productId, WarehouseId warehouseId) {
        return springRepository.existsByProductIdAndWarehouseId(productId.value(), warehouseId.value());
    }

    @Override
    public Mono<Void> save(InventoryItem item) {
        var entity = mapper.toEntity(item);
        return rxtx.execute(reactiveTransaction ->
            springRepository.save(entity)
                    .onErrorMap(OptimisticLockingFailureException.class, e ->
                            new InventoryDomainException(InventoryErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                    "Inventory was modified by another request. Please retry."))
                    .onErrorMap(DataIntegrityViolationException.class, e -> {
                        var msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (msg.contains("uq_inventory_product_warehouse")) {
                            return new InventoryDomainException(InventoryErrorCode.DUPLICATE_INVENTORY,
                                    "Inventory already exists for this product and warehouse");
                        }
                        return new InventoryDomainException(InventoryErrorCode.GENERIC,
                                "Data integrity violation: " + e.getMessage());
                    })
                    .then(Mono.defer(() -> {
                        var events = item.getDomainEvents();
                        item.clearDomainEvents();
                        return Flux.fromIterable(events)
                                .flatMap(event -> Mono.fromRunnable(() -> eventPublisher.publish(event))
                                        .subscribeOn(Schedulers.boundedElastic()))
                                .then();
                    }))
        ).then();
    }
}
