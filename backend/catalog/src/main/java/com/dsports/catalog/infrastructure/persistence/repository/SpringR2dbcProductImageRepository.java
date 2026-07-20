package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.infrastructure.persistence.entity.ProductImageEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SpringR2dbcProductImageRepository extends R2dbcRepository<ProductImageEntity, UUID> {
    Flux<ProductImageEntity> findByProductIdOrderByDisplayOrder(UUID productId);
}
