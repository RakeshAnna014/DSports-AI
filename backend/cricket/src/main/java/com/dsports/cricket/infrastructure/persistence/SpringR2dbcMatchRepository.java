package com.dsports.cricket.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import java.util.UUID;

public interface SpringR2dbcMatchRepository extends R2dbcRepository<MatchEntity, UUID> {
}
