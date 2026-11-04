package com.dsports.order.application.order.port;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Holds stock for an order while it awaits payment, and gives it back if the order is cancelled. */
public interface InventoryReservationPort {

    /** Reserves every line atomically with the caller's transaction; fails if any line lacks sellable stock. */
    Mono<Void> reserve(UUID orderId, List<ReservationItem> items);

    /** Returns held stock to sellable stock. Idempotent. */
    Mono<Void> release(UUID orderId);

    record ReservationItem(UUID productId, int quantity) {}
}
