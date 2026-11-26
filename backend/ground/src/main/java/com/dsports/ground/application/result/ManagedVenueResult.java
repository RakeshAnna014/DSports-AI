package com.dsports.ground.application.result;

import com.dsports.ground.domain.model.Venue;

import java.util.List;
import java.util.UUID;

/**
 * A venue as its owner or an administrator sees it: the public details, the owner, every court (inactive ones too) and the version to send back
 * with an edit (so a stale edit is refused, not merged). {@code ownerEligible} is false when the owner no longer holds the ground-owner role:
 * the venue then takes no new bookings until an administrator reassigns it.
 */
public record ManagedVenueResult(VenueResult venue, UUID ownerId, boolean ownerEligible, int version, List<CourtResult> courts) {

    public static ManagedVenueResult from(Venue v, boolean ownerEligible, List<CourtResult> courts) {
        return new ManagedVenueResult(VenueResult.from(v), v.getOwnerId(), ownerEligible, v.getVersion(), courts);
    }
}
