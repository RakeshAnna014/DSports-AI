package com.dsports.shipment.infrastructure.persistence.repository;

import com.dsports.shipment.infrastructure.persistence.entity.ShipmentTrackingEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SpringR2dbcShipmentTrackingRepository extends ReactiveCrudRepository<ShipmentTrackingEntity, UUID> {
    Flux<ShipmentTrackingEntity> findByShipmentIdOrderByEventTimeDesc(UUID shipmentId);
}
