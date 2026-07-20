package com.dsports.catalog.infrastructure.persistence.repository;

import com.dsports.catalog.infrastructure.persistence.entity.SportEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcSportRepository extends R2dbcRepository<SportEntity, UUID> {
}
