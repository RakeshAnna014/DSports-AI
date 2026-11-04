package com.dsports.shipment.application.port;

import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.TrackingNumber;
import reactor.core.publisher.Mono;

public interface CourierGateway {
    Mono<CourierGatewayResult> createShipment(Shipment shipment);
    Mono<CourierGatewayResult> trackShipment(TrackingNumber trackingNumber);
    Mono<CourierGatewayResult> cancelShipment(TrackingNumber trackingNumber);
    Mono<CourierGatewayResult> updateStatus(TrackingNumber trackingNumber, ShipmentStatus status);

    record CourierGatewayResult(boolean success, String message, String providerReference, String trackingData) {}
}
