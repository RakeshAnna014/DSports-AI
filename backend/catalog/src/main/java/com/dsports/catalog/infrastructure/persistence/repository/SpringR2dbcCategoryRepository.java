package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.infrastructure.persistence.entity.CategoryEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcCategoryRepository extends R2dbcRepository<CategoryEntity, UUID> {
}
