package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.GroundBlackout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface BlackoutRepository {

    /** Blackouts of a ground that overlap [from, to). */
    Flux<GroundBlackout> findOverlapping(UUID groundId, Instant from, Instant to);

    Mono<GroundBlackout> save(GroundBlackout blackout);
}
