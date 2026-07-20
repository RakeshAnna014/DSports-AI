package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.infrastructure.persistence.entity.BrandEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcBrandRepository extends R2dbcRepository<BrandEntity, UUID> {
}
