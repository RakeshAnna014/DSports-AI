package com.dsports.catalog.application.port;

import com.dsports.catalog.domain.model.Product;
import com.dsports.catalog.domain.model.ProductId;
import com.dsports.catalog.domain.model.SKU;
import com.dsports.catalog.domain.model.Slug;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProductRepository {
    Mono<Product> findById(ProductId id);
    Mono<Product> findBySku(SKU sku);
    Mono<Product> findBySlug(Slug slug);
    Mono<Boolean> existsBySku(SKU sku);
    Mono<Boolean> existsBySlug(Slug slug);
    Flux<Product> findAll();
    Flux<Product> findAll(ProductFilter filter);
    Mono<Void> save(Product product);
}
