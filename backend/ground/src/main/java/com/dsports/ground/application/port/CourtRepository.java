package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.Court;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

/** Courts are loaded together with their venue's opening hours, so a loaded court can compute its own slot grid. */
public interface CourtRepository {

    Mono<Court> findById(UUID id);

    /** All courts of a venue, active or not, by name. */
    Flux<Court> findByVenue(UUID venueId);

    /** Active courts of a venue, by name. */
    Flux<Court> findActiveByVenue(UUID venueId);

    Flux<Court> findAllById(Collection<UUID> ids);

    /** Inserts a new court or updates an existing one. A second court with the same name at the venue (any case) is {@code DUPLICATE_COURT_NAME}. */
    Mono<Court> save(Court court);
}
