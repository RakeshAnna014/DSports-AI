package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.application.port.ProductAttributeRepository;
import com.dsports.catalog.domain.model.ProductId;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ProductAttributeR2dbcRepositoryAdapter implements ProductAttributeRepository {

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transactionalOperator;

    public ProductAttributeR2dbcRepositoryAdapter(DatabaseClient databaseClient, TransactionalOperator transactionalOperator) {
        this.databaseClient = databaseClient;
        this.transactionalOperator = transactionalOperator;
    }

    @Override
    public Mono<Map<String, String>> findByProductId(ProductId productId) {
        return databaseClient.sql("SELECT attr_key, attr_value FROM product_attributes WHERE product_id = :id ORDER BY attr_key")
                .bind("id", productId.value())
                .map((row, meta) -> Map.entry(row.get("attr_key", String.class), row.get("attr_value", String.class)))
                .all()
                .collectList()
                .map(entries -> {
                    var result = new LinkedHashMap<String, String>();
                    entries.forEach(e -> result.put(e.getKey(), e.getValue()));
                    return (Map<String, String>) result;
                });
    }

    @Override
    public Mono<Map<UUID, Map<String, String>>> findAll() {
        return databaseClient.sql("SELECT product_id, attr_key, attr_value FROM product_attributes ORDER BY product_id, attr_key")
                .map((row, meta) -> new Object[]{row.get("product_id", UUID.class), row.get("attr_key", String.class), row.get("attr_value", String.class)})
                .all()
                .collectList()
                .map(rows -> {
                    var result = new LinkedHashMap<UUID, Map<String, String>>();
                    rows.forEach(r -> result.computeIfAbsent((UUID) r[0], k -> new LinkedHashMap<>()).put((String) r[1], (String) r[2]));
                    return (Map<UUID, Map<String, String>>) result;
                });
    }

    @Override
    public Mono<Void> replace(ProductId productId, Map<String, String> attributes) {
        var write = databaseClient.sql("DELETE FROM product_attributes WHERE product_id = :id")
                .bind("id", productId.value())
                .fetch().rowsUpdated()
                .thenMany(Flux.fromIterable(attributes.entrySet()))
                .concatMap(entry -> databaseClient.sql(
                                "INSERT INTO product_attributes (product_id, attr_key, attr_value) VALUES (:id, :k, :v)")
                        .bind("id", productId.value())
                        .bind("k", entry.getKey())
                        .bind("v", entry.getValue())
                        .fetch().rowsUpdated())
                .then();
        return transactionalOperator.transactional(write);
    }
}
