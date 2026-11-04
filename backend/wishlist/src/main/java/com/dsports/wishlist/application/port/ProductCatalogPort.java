package com.dsports.wishlist.application.port;

import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.UUID;

public interface ProductCatalogPort {
    Mono<ProductValidationResult> findActiveProduct(UUID productId);

    record ProductValidationResult(
        UUID productId,
        String name,
        String image,
        BigDecimal price,
        boolean active
    ) {}
}
