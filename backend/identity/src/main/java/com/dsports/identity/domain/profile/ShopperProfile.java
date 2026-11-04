package com.dsports.identity.domain.profile;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;

import java.time.Instant;
import java.util.UUID;

/**
 * A shopper profile owned by an account: the owner's own profile or a family member the owner manages. The owner is set once from the
 * authenticated identity and never changes; family members have no credentials and cannot sign in.
 */
public record ShopperProfile(UUID id, UUID ownerUserId, ProfileKind kind, String displayName, String relationship, AgeBand ageBand,
                             ShoppingPreferences preferences, boolean personalizationEnabled, Instant createdAt, Instant updatedAt) {

    public static final String OWNER_DISPLAY_NAME = "Me";

    public static ShopperProfile owner(UUID ownerUserId, Instant now) {
        return new ShopperProfile(UUID.randomUUID(), ownerUserId, ProfileKind.OWNER, OWNER_DISPLAY_NAME, null, null,
            ShoppingPreferences.empty(), true, now, now);
    }

    public static ShopperProfile family(UUID ownerUserId, String displayName, String relationship, AgeBand ageBand, ShoppingPreferences preferences,
                                        boolean personalizationEnabled, Instant now) {
        return new ShopperProfile(UUID.randomUUID(), ownerUserId, ProfileKind.FAMILY, name(displayName), optional(relationship, 30, "relationship"),
            required(ageBand), preferences, personalizationEnabled, now, now);
    }

    public ShopperProfile withPreferences(ShoppingPreferences updated, Instant now) {
        return new ShopperProfile(id, ownerUserId, kind, displayName, relationship, ageBand, updated, personalizationEnabled, createdAt, now);
    }

    public ShopperProfile withPersonalization(boolean enabled, Instant now) {
        return new ShopperProfile(id, ownerUserId, kind, displayName, relationship, ageBand, preferences, enabled, createdAt, now);
    }

    public ShopperProfile renamed(String newName, String newRelationship, AgeBand newAgeBand, Instant now) {
        if (kind == ProfileKind.OWNER) {
            throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "The account owner's own profile cannot be renamed or given an age band");
        }
        return new ShopperProfile(id, ownerUserId, kind, name(newName), optional(newRelationship, 30, "relationship"), required(newAgeBand),
            preferences, personalizationEnabled, createdAt, now);
    }

    public boolean isOwnedBy(UUID user) {
        return ownerUserId.equals(user);
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 60) {
            throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "A name of 1-60 characters is required");
        }
        return value.trim();
    }

    private static AgeBand required(AgeBand band) {
        if (band == null) {
            throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "An age band is required for a family member");
        }
        return band;
    }

    private static String optional(String value, int max, String what) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.trim().length() > max) {
            throw new IdentityDomainException(ErrorCode.VALIDATION_ERROR, "The " + what + " is limited to " + max + " characters");
        }
        return value.trim();
    }
}
