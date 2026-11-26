package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.OwnerEligibilityPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Blackout;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.SlotCalculator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The slots of one court on one local calendar day, computed (never stored) from the court's grid, its active bookings and the blackouts that
 * close it (venue-wide or its own). A HELD booking blocks its slot until the expiry job releases it, exactly as the database constraint does.
 * While the venue's owner no longer holds the ground-owner role every slot is reported unavailable ({@code NOT_ACCEPTING}).
 */
public class GetCourtSlotsUseCase {

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final OwnerEligibilityPort owners;
    private final BookingPolicy policy;
    private final Clock clock;

    public GetCourtSlotsUseCase(VenueRepository venues, CourtRepository courts, BookingRepository bookings, BlackoutRepository blackouts,
                                OwnerEligibilityPort owners, BookingPolicy policy, Clock clock) {
        this.venues = venues;
        this.courts = courts;
        this.bookings = bookings;
        this.blackouts = blackouts;
        this.owners = owners;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<List<SlotResult>> execute(UUID venueId, UUID courtId, LocalDate date) {
        return venues.findById(venueId)
            .filter(venue -> venue.isActive())
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + venueId)))
            .flatMap(venue -> VenueAccess.courtOf(courts, venueId, courtId)
                .filter(Court::isActive)
                .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.COURT_NOT_FOUND, "Court not found: " + courtId)))
                .flatMap(court -> Mono.zip(availability(court, date), owners.isOwner(venue.getOwnerId()))
                    .map(t -> t.getT1().stream().map(a -> t.getT2()
                        ? SlotResult.from(a)
                        : new SlotResult(a.slot().start(), a.slot().end(), a.price(), false, "NOT_ACCEPTING")).toList())));
    }

    /** Shared with the search use case. */
    Mono<List<SlotCalculator.SlotAvailability>> availability(Court court, LocalDate date) {
        var slots = court.slotsOn(date);
        if (slots.isEmpty()) {
            return Mono.just(List.of());
        }
        var from = slots.get(0).start();
        var to = slots.get(slots.size() - 1).end();
        var now = clock.instant();
        return Mono.zip(
                bookings.findOccupiedPeriods(court.getId(), from, to).collectList(),
                blackouts.findOverlapping(court.getVenueId(), court.getId(), from, to).map(Blackout::period).collectList())
            .map(t -> SlotCalculator.availability(court, date, t.getT1(), t.getT2(), now, policy));
    }
}
