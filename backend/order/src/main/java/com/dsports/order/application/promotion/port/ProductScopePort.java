package com.dsports.order.application.promotion.port;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Catalogue attributes (category, sport) the promotion rules need for the products in a checkout. */
public interface ProductScopePort {

    Mono<Map<UUID, Scope>> scopesOf(Collection<UUID> productIds);

    /** Display names of the sports or categories a scoped promotion applies to (empty for ALL). */
    Mono<java.util.List<String>> scopeNames(com.dsports.order.domain.promotion.model.Promotion.ScopeType type, Collection<UUID> scopeIds);

    record Scope(UUID categoryId, UUID sportId) {}
}
