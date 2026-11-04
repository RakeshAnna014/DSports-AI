package com.dsports.catalog.application.port;

import com.dsports.catalog.domain.model.ProductId;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

public interface ProductAttributeRepository {

    /** Attributes of a product ordered by key; empty map when none are recorded. */
    Mono<Map<String, String>> findByProductId(ProductId productId);

    /** Attributes of every product, keyed by product id (products with none are absent). */
    Mono<Map<UUID, Map<String, String>>> findAll();

    /** Replaces the complete attribute set of a product in one transaction. */
    Mono<Void> replace(ProductId productId, Map<String, String> attributes);
}
