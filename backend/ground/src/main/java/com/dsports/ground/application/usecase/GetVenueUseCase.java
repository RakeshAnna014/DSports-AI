package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.VenueDetailResult;
import com.dsports.ground.application.result.VenueResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** A single active venue with its active courts, for everyone. An inactive venue looks exactly like a missing one. */
public class GetVenueUseCase {

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final VenueAssembler assembler;

    public GetVenueUseCase(VenueRepository venues, CourtRepository courts, VenueAssembler assembler) {
        this.venues = venues;
        this.courts = courts;
        this.assembler = assembler;
    }

    public Mono<VenueDetailResult> execute(UUID venueId) {
        return venues.findById(venueId)
            .filter(venue -> venue.isActive())
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + venueId)))
            .flatMap(venue -> Mono.zip(
                    courts.findActiveByVenue(venueId).collectList().flatMap(assembler::courtResults),
                    assembler.eligibleOwners(java.util.List.of(venue.getOwnerId())))
                .map(t -> new VenueDetailResult(VenueResult.from(venue), t.getT1(), t.getT2().contains(venue.getOwnerId()))));
    }
}
