package com.dsports.ground.application.port;

import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Whether a user currently holds the ground-owner role, read live from the identity data (never from a token). A venue whose owner no longer
 * holds it takes no new bookings and is hidden from search; existing bookings stay valid. Bootstrap implements this over the identity tables.
 */
public interface OwnerEligibilityPort {

    /** The subset of {@code userIds} that currently hold the ground-owner role. */
    Mono<Set<UUID>> ownersAmong(Collection<UUID> userIds);

    default Mono<Boolean> isOwner(UUID userId) {
        return ownersAmong(java.util.List.of(userId)).map(found -> found.contains(userId));
    }
}
