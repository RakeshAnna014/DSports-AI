package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface SpringR2dbcVenueRepository extends R2dbcRepository<VenueEntity, UUID> {

    Flux<VenueEntity> findByOwnerIdOrderByNameAsc(UUID ownerId);

    Flux<VenueEntity> findAllByOrderByNameAsc();
}
