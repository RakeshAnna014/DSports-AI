package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcCourtRepository extends R2dbcRepository<CourtEntity, UUID> {
}
