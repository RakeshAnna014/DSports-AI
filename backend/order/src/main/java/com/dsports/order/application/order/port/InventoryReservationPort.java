package com.dsports.order.application.order.port;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public interface InventoryReservationPort {
    Mono<Void> reserveInventory(List<ReservationItem> items);

    record ReservationItem(UUID productId, int quantity) {}
}
