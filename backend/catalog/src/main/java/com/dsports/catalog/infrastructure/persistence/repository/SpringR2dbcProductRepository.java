package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcProductRepository extends R2dbcRepository<ProductEntity, UUID> {
}
