package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.Blackout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface BlackoutRepository {

    /** Blackouts that close the court ({@code courtId}): the venue-wide ones and the ones naming this court, overlapping [from, to). */
    Flux<Blackout> findOverlapping(UUID venueId, UUID courtId, Instant from, Instant to);

    /** Every blackout of the venue (venue-wide and per court) overlapping [from, to), for the owner's list. */
    Flux<Blackout> findByVenue(UUID venueId, Instant from, Instant to);

    Mono<Blackout> save(Blackout blackout);
}
