package com.dsports.identity.application.usecase;

import com.dsports.identity.application.port.ShopperProfileRepository;
import com.dsports.identity.application.port.SportTaxonomyPort;
import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.identity.domain.profile.AgeBand;
import com.dsports.identity.domain.profile.ProfileKind;
import com.dsports.identity.domain.profile.ShopperProfile;
import com.dsports.identity.domain.profile.ShoppingPreferences;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Preferences and family profiles for the authenticated account. Every method takes the owner from the authenticated identity and
 * resolves a profile only through {@code findByIdAndOwner}, so another account's profile id behaves exactly like a missing one.
 */
public class ShopperProfileUseCase {

    public static final int MAX_FAMILY_PROFILES = 6;

    public record Overview(UUID activeProfileId, List<ShopperProfile> profiles) {}

    private final ShopperProfileRepository repository;
    private final SportTaxonomyPort taxonomy;
    private final Clock clock;

    public ShopperProfileUseCase(ShopperProfileRepository repository, SportTaxonomyPort taxonomy, Clock clock) {
        this.repository = repository;
        this.taxonomy = taxonomy;
        this.clock = clock;
    }

    public Mono<Overview> overview(UUID owner) {
        return ownerProfile(owner).then(Mono.zip(repository.findByOwner(owner).collectList(), activeId(owner)))
            .map(t -> new Overview(t.getT2(), t.getT1()));
    }

    /** The profile used for personalisation: the selected one, falling back to the owner's own. */
    public Mono<ShopperProfile> active(UUID owner) {
        return activeId(owner).flatMap(id -> get(owner, id));
    }

    public Mono<ShopperProfile> get(UUID owner, UUID profileId) {
        return ownerProfile(owner).then(find(owner, profileId));
    }

    public Mono<ShopperProfile> select(UUID owner, UUID profileId) {
        return get(owner, profileId).flatMap(p -> repository.setActive(owner, p.id()).thenReturn(p));
    }

    public Mono<ShopperProfile> updatePreferences(UUID owner, UUID profileId, ShoppingPreferences requested, Boolean personalization) {
        return get(owner, profileId).flatMap(p -> checkSports(requested).flatMap(prefs -> {
            var now = clock.instant();
            var updated = p.withPreferences(prefs, now);
            if (personalization != null) {
                updated = updated.withPersonalization(personalization, now);
            }
            return save(updated);
        }));
    }

    /** Clears every preference of the profile (it stays, with no signal). */
    public Mono<ShopperProfile> resetPreferences(UUID owner, UUID profileId) {
        return get(owner, profileId).flatMap(p -> save(p.withPreferences(ShoppingPreferences.empty(), clock.instant())));
    }

    public Mono<ShopperProfile> createFamily(UUID owner, String name, String relationship, AgeBand ageBand, ShoppingPreferences requested,
                                             boolean personalization) {
        return ownerProfile(owner).then(repository.countFamily(owner)).flatMap(count -> {
            if (count >= MAX_FAMILY_PROFILES) {
                return Mono.<ShopperProfile>error(new IdentityDomainException(ErrorCode.MAX_FAMILY_PROFILES_EXCEEDED,
                    "At most " + MAX_FAMILY_PROFILES + " family profiles are allowed"));
            }
            return checkSports(requested).flatMap(prefs ->
                save(ShopperProfile.family(owner, name, relationship, ageBand, prefs, personalization, clock.instant())));
        });
    }

    public Mono<ShopperProfile> updateFamily(UUID owner, UUID profileId, String name, String relationship, AgeBand ageBand) {
        return find(owner, profileId).flatMap(p -> {
            if (p.kind() != ProfileKind.FAMILY) {
                return Mono.<ShopperProfile>error(new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "Only family profiles can be edited here"));
            }
            return save(p.renamed(name, relationship, ageBand, clock.instant()));
        });
    }

    /** Deletes the family profile and everything stored for it. If it was the active profile the owner's own profile becomes active again. */
    public Mono<Void> deleteFamily(UUID owner, UUID profileId) {
        return find(owner, profileId).flatMap(p -> {
            if (p.kind() != ProfileKind.FAMILY) {
                return Mono.<Void>error(new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "The account owner's own profile cannot be deleted"));
            }
            return repository.deleteFamily(profileId, owner).then();
        });
    }

    // ------------------------------------------------------------------------------------------------ internals

    private Mono<ShopperProfile> ownerProfile(UUID owner) {
        return repository.ensureOwnerProfile(ShopperProfile.owner(owner, clock.instant()));
    }

    private Mono<UUID> activeId(UUID owner) {
        return ownerProfile(owner).flatMap(own -> repository.activeProfileId(owner).defaultIfEmpty(own.id()));
    }

    private Mono<ShopperProfile> find(UUID owner, UUID profileId) {
        return repository.findByIdAndOwner(profileId, owner)
            .switchIfEmpty(Mono.error(new IdentityDomainException(ErrorCode.PROFILE_NOT_FOUND, "Profile not found")));
    }

    private Mono<ShopperProfile> save(ShopperProfile profile) {
        return repository.save(profile).thenReturn(profile);
    }

    /** Sports must be sports the catalog sells; the stored value uses the catalog's own spelling. */
    private Mono<ShoppingPreferences> checkSports(ShoppingPreferences requested) {
        if (requested.sports().isEmpty()) {
            return Mono.just(requested);
        }
        return taxonomy.activeSportNames().map(known -> {
            var canonical = known.stream().collect(java.util.stream.Collectors.toMap(n -> n.toLowerCase(Locale.ROOT), n -> n, (a, b) -> a));
            var resolved = requested.sports().stream().map(s -> {
                var match = canonical.get(s.toLowerCase(Locale.ROOT));
                if (match == null) {
                    throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "'" + s + "' is not a sport DSports sells. Available: " + canonical.values().stream().sorted().toList());
                }
                return match;
            }).toList();
            return new ShoppingPreferences(resolved, requested.favoriteBrands(), requested.favoriteCategories(), requested.favoriteTeams(),
                requested.usageLevel(), requested.priceMin(), requested.priceMax(), requested.sizes(), requested.attributes());
        });
    }
}
