package com.dsports.inventory.infrastructure.persistence.repository;

import com.dsports.inventory.infrastructure.persistence.entity.StockReservationEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcStockReservationRepository extends R2dbcRepository<StockReservationEntity, UUID> {

    Flux<StockReservationEntity> findByOrderId(UUID orderId);

    Mono<StockReservationEntity> findByOrderIdAndProductId(UUID orderId, UUID productId);
}
