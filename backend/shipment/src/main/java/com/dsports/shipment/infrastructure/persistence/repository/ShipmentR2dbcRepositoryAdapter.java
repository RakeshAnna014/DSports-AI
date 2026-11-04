package com.dsports.shipment.infrastructure.persistence.repository;

import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentTracking;
import com.dsports.shipment.domain.model.TrackingNumber;
import com.dsports.shipment.infrastructure.persistence.mapper.ShipmentEntityMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class ShipmentR2dbcRepositoryAdapter implements ShipmentRepository {
    private final SpringR2dbcShipmentRepository springRepository;
    private final SpringR2dbcShipmentTrackingRepository trackingRepository;
    private final EventPublisher eventPublisher;
    private final TransactionalOperator transactionalOperator;

    public ShipmentR2dbcRepositoryAdapter(SpringR2dbcShipmentRepository springRepository,
                                           SpringR2dbcShipmentTrackingRepository trackingRepository,
                                           EventPublisher eventPublisher,
                                           ReactiveTransactionManager transactionManager) {
        this.springRepository = springRepository;
        this.trackingRepository = trackingRepository;
        this.eventPublisher = eventPublisher;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @Override
    public Mono<Shipment> findById(ShipmentId id) {
        return springRepository.findById(id.value())
                .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    public Mono<Shipment> findByOrderId(UUID orderId) {
        return springRepository.findByOrderId(orderId)
                .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    public Mono<Shipment> findByTrackingNumber(TrackingNumber trackingNumber) {
        return springRepository.findByTrackingNumber(trackingNumber.value())
                .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    public Flux<Shipment> findByUserId(UUID userId) {
        return springRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    public Flux<Shipment> findAll(int page, int size) {
        var offset = page * size;
        return springRepository.findAllPaged(offset, size)
                .map(ShipmentEntityMapper::toDomain);
    }

    @Override
    public Mono<Long> countByUserId(UUID userId) {
        return springRepository.countByUserId(userId);
    }

    @Override
    public Mono<Long> countAll() {
        return springRepository.count();
    }

    @Override
    public Mono<Void> save(Shipment shipment) {
        var entity = ShipmentEntityMapper.toEntity(shipment);
        var id = shipment.getId().value();

        return transactionalOperator.execute(tx ->
            springRepository.save(entity)
                    .onErrorMap(OptimisticLockingFailureException.class, e ->
                            new ShipmentDomainException(ShipmentErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                    "Concurrent modification detected for shipment " + id))
                    .onErrorMap(DataIntegrityViolationException.class, e ->
                            new ShipmentDomainException(ShipmentErrorCode.ORDER_ALREADY_SHIPPED,
                                    "Order " + shipment.getOrderId() + " already has a shipment"))
                    .map(savedEntity -> {
                        shipment.setVersion(savedEntity.getVersion());
                        return savedEntity;
                    })
                    .then()
        ).then(Mono.fromRunnable(() -> publishEvents(shipment)));
    }

    @Override
    public Mono<Void> saveTracking(ShipmentTracking tracking) {
        var entity = ShipmentEntityMapper.toTrackingEntity(tracking, tracking.getShipmentId().value());
        return transactionalOperator.execute(tx ->
            trackingRepository.save(entity).then()
        ).then();
    }

    @Override
    public Flux<ShipmentTracking> findTrackingByShipmentId(ShipmentId shipmentId) {
        return trackingRepository.findByShipmentIdOrderByEventTimeDesc(shipmentId.value())
                .map(ShipmentEntityMapper::toTrackingDomain);
    }

    private void publishEvents(Shipment shipment) {
        if (!shipment.getDomainEvents().isEmpty()) {
            shipment.getDomainEvents().forEach(eventPublisher::publish);
            shipment.clearDomainEvents();
        }
    }
}
