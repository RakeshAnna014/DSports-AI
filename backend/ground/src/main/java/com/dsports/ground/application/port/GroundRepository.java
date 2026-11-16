package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.Ground;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface GroundRepository {

    Mono<Ground> findById(UUID id);

    /** Active grounds, optionally narrowed to one city (case-insensitive), ordered by name. */
    Flux<Ground> findActive(String cityOrNull);

    Flux<Ground> findByOwner(UUID ownerId);

    Flux<Ground> findAllById(Collection<UUID> ids);

    /** Inserts a new ground or updates an existing one (optimistic locking on the version). */
    Mono<Ground> save(Ground ground);
}
