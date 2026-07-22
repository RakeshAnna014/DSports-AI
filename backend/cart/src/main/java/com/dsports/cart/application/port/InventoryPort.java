package com.dsports.cart.application.port;

import reactor.core.publisher.Mono;
import java.util.UUID;

public interface InventoryPort {
    Mono<InventoryCheckResult> checkAvailability(UUID productId, int requestedQuantity);

    record InventoryCheckResult(
        UUID productId,
        int availableQuantity,
        boolean sufficient
    ) {}
}
