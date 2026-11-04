package com.dsports.shipment.application.port;

import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentTracking;
import com.dsports.shipment.domain.model.TrackingNumber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ShipmentRepository {
    Mono<Shipment> findById(ShipmentId id);
    Mono<Shipment> findByOrderId(UUID orderId);
    Mono<Shipment> findByTrackingNumber(TrackingNumber trackingNumber);
    Flux<Shipment> findByUserId(UUID userId);
    Flux<Shipment> findAll(int page, int size);
    Mono<Long> countByUserId(UUID userId);
    Mono<Long> countAll();
    Mono<Void> save(Shipment shipment);
    Mono<Void> saveTracking(ShipmentTracking tracking);
    Flux<ShipmentTracking> findTrackingByShipmentId(ShipmentId shipmentId);
}
