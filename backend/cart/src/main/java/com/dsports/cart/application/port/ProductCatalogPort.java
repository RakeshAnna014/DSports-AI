package com.dsports.cart.application.port;

import reactor.core.publisher.Mono;
import java.util.UUID;

public interface ProductCatalogPort {
    Mono<ProductValidationResult> findActiveProduct(UUID productId);

    record ProductValidationResult(
        UUID productId,
        String name,
        boolean active
    ) {}
}
