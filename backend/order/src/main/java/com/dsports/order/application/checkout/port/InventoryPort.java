package com.dsports.order.application.checkout.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface InventoryPort {
    Mono<InventoryResult> checkAvailability(UUID productId, int requestedQuantity);

    record InventoryResult(UUID productId, int availableQuantity, boolean sufficient) {}
}
