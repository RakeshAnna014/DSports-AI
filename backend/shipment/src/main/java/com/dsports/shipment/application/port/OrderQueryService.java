package com.dsports.shipment.application.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** Read access to the order a shipment fulfils. */
public interface OrderQueryService {

    /** Empty when the order does not exist. */
    Mono<OrderForShipment> findOrder(UUID orderId);

    /**
     * The order's immutable delivery address snapshot, taken when the order was placed.
     *
     * @param paid true once the order is CONFIRMED or later in fulfilment (PROCESSING, SHIPPED)
     */
    record OrderForShipment(UUID orderId, UUID userId, boolean paid, String street, String city,
                            String state, String zipCode, String country) {}
}
