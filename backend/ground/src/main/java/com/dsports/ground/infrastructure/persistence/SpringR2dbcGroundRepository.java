package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SpringR2dbcGroundRepository extends R2dbcRepository<GroundEntity, UUID> {

    Flux<GroundEntity> findByActiveTrueOrderByNameAsc();

    Flux<GroundEntity> findByActiveTrueAndCityIgnoreCaseOrderByNameAsc(String city);

    Flux<GroundEntity> findByOwnerIdOrderByNameAsc(UUID ownerId);
}
