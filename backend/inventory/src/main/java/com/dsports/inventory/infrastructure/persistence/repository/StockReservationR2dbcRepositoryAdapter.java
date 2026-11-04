package com.dsports.inventory.infrastructure.persistence.repository;

import com.dsports.inventory.application.port.StockReservationRepository;
import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.ProductId;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.inventory.domain.model.ReservationStatus;
import com.dsports.inventory.domain.model.StockReservation;
import com.dsports.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class StockReservationR2dbcRepositoryAdapter implements StockReservationRepository {

    private final SpringR2dbcStockReservationRepository springRepository;

    public StockReservationR2dbcRepositoryAdapter(SpringR2dbcStockReservationRepository springRepository) {
        this.springRepository = springRepository;
    }

    @Override
    public Flux<StockReservation> findByOrderId(UUID orderId) {
        return springRepository.findByOrderId(orderId).map(StockReservationR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<StockReservation> findByOrderIdAndProductId(UUID orderId, ProductId productId) {
        return springRepository.findByOrderIdAndProductId(orderId, productId.value())
                .map(StockReservationR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<Void> save(StockReservation reservation) {
        return springRepository.save(toEntity(reservation))
                .doOnNext(saved -> reservation.setVersion(saved.getVersion()))
                .onErrorMap(OptimisticLockingFailureException.class, e ->
                        new InventoryDomainException(InventoryErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                                "Reservation " + reservation.getId() + " was modified concurrently"))
                .onErrorMap(DataIntegrityViolationException.class, e ->
                        new InventoryDomainException(InventoryErrorCode.RESERVATION_CONFLICT,
                                "Stock for product " + reservation.getProductId().value()
                                        + " is already reserved for order " + reservation.getOrderId()))
                .then();
    }

    private static StockReservationEntity toEntity(StockReservation reservation) {
        var entity = new StockReservationEntity();
        entity.setId(reservation.getId());
        entity.setOrderId(reservation.getOrderId());
        entity.setProductId(reservation.getProductId().value());
        entity.setInventoryId(reservation.getInventoryId().value());
        entity.setQuantity(reservation.getQuantity().value());
        entity.setStatus(reservation.getStatus().name());
        entity.setVersion(reservation.getVersion());
        entity.setCreatedAt(reservation.getCreatedAt());
        entity.setUpdatedAt(reservation.getUpdatedAt());
        return entity;
    }

    private static StockReservation toDomain(StockReservationEntity entity) {
        return StockReservation.reconstitute(entity.getId(), entity.getOrderId(),
                ProductId.fromUUID(entity.getProductId()), InventoryId.fromUUID(entity.getInventoryId()),
                Quantity.from(entity.getQuantity()), ReservationStatus.valueOf(entity.getStatus()),
                entity.getVersion(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
