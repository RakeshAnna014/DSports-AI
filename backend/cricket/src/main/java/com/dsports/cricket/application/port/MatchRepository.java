package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.Match;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface MatchRepository {

    /** The match with its playing XIs and scorers, or empty. */
    Mono<Match> findById(UUID id);

    /** Matches {@code user} created, may score, or has a team in (as creator or through a linked player), newest first. */
    Flux<Match> findMine(UUID user);

    /**
     * Matches being played now (in an innings or at the innings break), most recently scored first. {@code city} is matched without regard to case; null means
     * every city. {@code limit} and {@code offset} page the list. This is for the public list, so it is not limited to anyone's own matches.
     */
    Flux<Match> findLive(String city, int limit, int offset);

    /**
     * Inserts or updates the match and replaces its XIs and scorers, in the caller's transaction. A stale version is {@code OPTIMISTIC_LOCKING_CONFLICT};
     * a venue booking already used by another match is {@code BOOKING_ALREADY_USED}.
     */
    Mono<Match> save(Match match);
}
