package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.Player;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.UUID;

public interface PlayerRepository {

    Mono<Player> findById(UUID id);

    Flux<Player> findAllById(Collection<UUID> ids);

    /** Players {@code user} may use: the ones they created and the one linked to their own account, by name. */
    Flux<Player> findUsableBy(UUID user);

    /** The player linked to this customer's account, if any (a customer has at most one). */
    Mono<Player> findByCustomer(UUID customerId);

    /** Guests (not linked to any account) with exactly this normalised phone number, for the claim hint. */
    Flux<Player> findUnlinkedByPhone(String normalisedPhone, int limit);

    /**
     * Inserts or updates (optimistic locking on the version). A second player with the same phone for the same creator is
     * {@code DUPLICATE_PLAYER_PHONE}; a second player linked to the same customer is {@code CLAIMANT_ALREADY_LINKED}.
     */
    Mono<Player> save(Player player);
}
