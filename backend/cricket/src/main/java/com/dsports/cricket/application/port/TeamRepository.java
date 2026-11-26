package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.Team;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TeamRepository {

    /** The team with its members, or empty. */
    Mono<Team> findById(UUID id);

    /** Teams {@code user} created, or has a linked player in, by name. */
    Flux<Team> findMine(UUID user);

    /**
     * Inserts or updates the team and replaces its member list, in the caller's transaction. A stale version is
     * {@code OPTIMISTIC_LOCKING_CONFLICT}; a second team with the same name for the same creator is {@code DUPLICATE_TEAM_NAME}.
     */
    Mono<Team> save(Team team);
}
