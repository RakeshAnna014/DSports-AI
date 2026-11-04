package com.dsports.identity.application.port;

import com.dsports.identity.domain.profile.ShopperProfile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ShopperProfileRepository {

    Flux<ShopperProfile> findByOwner(UUID ownerUserId);

    /** Only returns the profile when it belongs to the owner; anything else is empty (indistinguishable from missing). */
    Mono<ShopperProfile> findByIdAndOwner(UUID id, UUID ownerUserId);

    /** Creates the owner's own profile if it does not exist yet (idempotent under concurrency) and returns it. */
    Mono<ShopperProfile> ensureOwnerProfile(ShopperProfile candidate);

    Mono<Long> countFamily(UUID ownerUserId);

    /** Insert or update by id. */
    Mono<Void> save(ShopperProfile profile);

    /** Deletes a family profile of this owner; false when there was none. The owner's own profile is never deleted here. */
    Mono<Boolean> deleteFamily(UUID id, UUID ownerUserId);

    Mono<UUID> activeProfileId(UUID ownerUserId);

    Mono<Void> setActive(UUID ownerUserId, UUID profileId);
}
