package com.dsports.order.infrastructure.promotion.persistence;

import com.dsports.order.application.promotion.port.ProductScopePort;
import com.dsports.order.domain.promotion.model.Promotion.ScopeType;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only projection of the catalogue's category and sport for the products in a checkout (same approach as the co-purchase adapter). */
public class ProductScopeR2dbcAdapter implements ProductScopePort {

    private final DatabaseClient db;

    public ProductScopeR2dbcAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Map<UUID, Scope>> scopesOf(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return db.sql("SELECT id, category_id, sport_id FROM products WHERE id IN (:ids)")
            .bind("ids", productIds)
            .map((row, meta) -> Map.entry(row.get("id", UUID.class), new Scope(row.get("category_id", UUID.class), row.get("sport_id", UUID.class))))
            .all().collect(HashMap<UUID, Scope>::new, (m, e) -> m.put(e.getKey(), e.getValue())).map(m -> (Map<UUID, Scope>) m);
    }

    @Override
    public Mono<List<String>> scopeNames(ScopeType type, Collection<UUID> scopeIds) {
        if (type == ScopeType.ALL || scopeIds.isEmpty()) {
            return Mono.just(List.of());
        }
        var table = type == ScopeType.SPORT ? "sports" : "categories";     // fixed identifiers, never user input
        return db.sql("SELECT name FROM " + table + " WHERE id IN (:ids) ORDER BY name")
            .bind("ids", scopeIds)
            .map((row, meta) -> row.get("name", String.class)).all().collectList();
    }
}
