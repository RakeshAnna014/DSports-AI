package com.dsports.shipment.infrastructure.gateway;

import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.CourierGateway.CourierGatewayResult;
import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class MockCourierGateway implements CourierGateway {
    private static final Logger log = LoggerFactory.getLogger(MockCourierGateway.class);

    @Override
    public Mono<CourierGatewayResult> createShipment(Shipment shipment) {
        var trackingNumber = TrackingNumber.generate();
        log.info("[MOCK COURIER] Creating shipment {} with tracking {}", shipment.getShipmentNumber(), trackingNumber.value());
        // providerReference is the courier's tracking (AWB) number, which the use cases persist on the shipment.
        return Mono.just(new CourierGatewayResult(true, "Shipment created with mock courier", trackingNumber.value(),
            "Shipment registered with mock courier"));
    }

    @Override
    public Mono<CourierGatewayResult> trackShipment(TrackingNumber trackingNumber) {
        log.info("[MOCK COURIER] Tracking {}", trackingNumber.value());
        return Mono.just(new CourierGatewayResult(true, "In transit", "mock-ref", "Last scanned at sorting facility"));
    }

    @Override
    public Mono<CourierGatewayResult> cancelShipment(TrackingNumber trackingNumber) {
        log.info("[MOCK COURIER] Cancelling shipment {}", trackingNumber.value());
        return Mono.just(new CourierGatewayResult(true, "Shipment cancelled", "mock-ref", null));
    }

    @Override
    public Mono<CourierGatewayResult> updateStatus(TrackingNumber trackingNumber, ShipmentStatus status) {
        log.info("[MOCK COURIER] Updating status for {} to {}", trackingNumber.value(), status);
        return Mono.just(new CourierGatewayResult(true, "Status updated to " + status, "mock-ref", status.name()));
    }
}
