package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.PlayerClaim;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ClaimRepository {

    Mono<PlayerClaim> findById(UUID id);

    /** Pending claims on guests that {@code creator} created, oldest first. */
    Flux<PlayerClaim> findPendingForCreator(UUID creator);

    /** Every pending claim, oldest first (administrators). */
    Flux<PlayerClaim> findPending(int limit);

    Flux<PlayerClaim> findPendingByPlayer(UUID playerId);

    /** The claimant's own claims, newest first. */
    Flux<PlayerClaim> findByClaimant(UUID claimant);

    Mono<Boolean> existsPending(UUID playerId, UUID claimant);

    Mono<PlayerClaim> save(PlayerClaim claim);
}
