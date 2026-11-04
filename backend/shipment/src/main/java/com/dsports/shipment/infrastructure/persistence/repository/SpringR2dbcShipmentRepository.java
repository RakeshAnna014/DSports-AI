package com.dsports.shipment.infrastructure.persistence.repository;

import com.dsports.shipment.infrastructure.persistence.entity.ShipmentEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcShipmentRepository extends ReactiveCrudRepository<ShipmentEntity, UUID> {
    Mono<ShipmentEntity> findByOrderId(UUID orderId);
    Mono<ShipmentEntity> findByTrackingNumber(String trackingNumber);
    Flux<ShipmentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
    @Query("SELECT * FROM shipments ORDER BY created_at DESC OFFSET :offset LIMIT :limit")
    Flux<ShipmentEntity> findAllPaged(int offset, int limit);
    Mono<Long> countByUserId(UUID userId);
}
