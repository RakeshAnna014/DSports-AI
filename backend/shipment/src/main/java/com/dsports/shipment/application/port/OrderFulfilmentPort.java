package com.dsports.shipment.application.port;

import com.dsports.shipment.domain.model.ShipmentStatus;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Keeps the order's fulfilment status in step with its shipment (shipped once picked up, delivered on delivery). */
public interface OrderFulfilmentPort {

    Mono<Void> shipmentProgressed(UUID orderId, ShipmentStatus shipmentStatus);
}
