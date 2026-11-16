package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.Venue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface VenueRepository {

    Mono<Venue> findById(UUID id);

    /**
     * Active venues that have at least one active court offering {@code sportIdOrNull} (any sport when null), optionally in one city.
     * The city match ignores case and surrounding spaces. Ordered by name.
     */
    Flux<Venue> findActive(UUID sportIdOrNull, String cityOrNull);

    Flux<Venue> findByOwner(UUID ownerId);

    /** Every venue, active or not (administrators). */
    Flux<Venue> findAll();

    Flux<Venue> findAllById(Collection<UUID> ids);

    /** Inserts a new venue or updates an existing one (optimistic locking on the version). */
    Mono<Venue> save(Venue venue);
}
