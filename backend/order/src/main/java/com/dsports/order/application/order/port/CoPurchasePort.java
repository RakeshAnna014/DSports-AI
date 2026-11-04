package com.dsports.order.application.order.port;

import reactor.core.publisher.Flux;

import java.util.UUID;

/** Read-only evidence of what is bought together, aggregated across all customers' non-cancelled orders. */
public interface CoPurchasePort {

    /** A product that appeared in {@code orders} distinct orders that also contained the reference product. */
    record CoPurchase(UUID productId, long orders) {}

    /** Only products seen together in at least {@code minOrders} distinct orders, strongest first. */
    Flux<CoPurchase> coPurchased(UUID productId, int minOrders, int limit);
}
