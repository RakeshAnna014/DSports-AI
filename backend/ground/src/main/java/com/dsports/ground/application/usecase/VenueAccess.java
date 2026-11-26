package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** The access rule shared by every management use case: an owner can only touch their own venues, an administrator any venue. */
final class VenueAccess {

    private VenueAccess() {
    }

    /** Loads a venue for its manager. A missing venue is 404; someone else's venue is 403 (unless the caller is an administrator). */
    static Mono<Venue> accessible(VenueRepository venues, Caller caller, UUID venueId) {
        return venues.findById(venueId)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + venueId)))
            .flatMap(venue -> caller.admin() || venue.getOwnerId().equals(caller.id())
                ? Mono.just(venue)
                : Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_OWNED_BY_USER, "This venue does not belong to you")));
    }

    /** Loads a court of the given venue; a court that exists elsewhere looks exactly like a missing one. */
    static Mono<Court> courtOf(CourtRepository courts, UUID venueId, UUID courtId) {
        return courts.findById(courtId)
            .filter(court -> court.getVenueId().equals(venueId))
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId)));
    }
}
