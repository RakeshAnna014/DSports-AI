package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Blackout;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Blackouts (maintenance, private events) for a whole venue or one of its courts. A blackout cannot cover a held or confirmed booking: those
 * customers are not silently cancelled; the owner must cancel them first. Adding one takes the same per-venue lock as holding a slot, so a
 * blackout and a booking can never both win the same time.
 */
public class ManageBlackoutsUseCase {

    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofDays(90);

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ManageBlackoutsUseCase(VenueRepository venues, CourtRepository courts, BookingRepository bookings, BlackoutRepository blackouts,
                                  TransactionRunner transactions, Clock clock) {
        this.venues = venues;
        this.courts = courts;
        this.bookings = bookings;
        this.blackouts = blackouts;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** {@code courtId} null closes the whole venue. */
    public Mono<Blackout> add(Caller caller, UUID venueId, UUID courtId, Instant start, Instant end, String reason) {
        return transactions.inTransaction(() -> bookings.lockVenue(venueId)
            .then(Mono.defer(() -> VenueAccess.accessible(venues, caller, venueId)))
            .then(Mono.defer(() -> courtId == null ? Mono.<Void>empty() : VenueAccess.courtOf(courts, venueId, courtId).then()))
            .then(Mono.defer(() -> {
                var now = clock.instant();
                var blackout = Blackout.create(venueId, courtId, start, end, reason, caller.id(), now);
                if (!end.isAfter(now)) {
                    return Mono.<Blackout>error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A blackout must end in the future"));
                }
                var occupied = courtId == null ? bookings.findOccupiedPeriodsAtVenue(venueId, start, end) : bookings.findOccupiedPeriods(courtId, start, end);
                return occupied.count().flatMap(active -> active > 0
                    ? Mono.<Blackout>error(new GroundDomainException(GroundErrorCode.BLACKOUT_CONFLICT,
                        active + " booking(s) overlap this period. Cancel them first, then add the blackout."))
                    : blackouts.save(blackout));
            })));
    }

    /** The venue's blackouts (venue-wide and per court) overlapping [from, to); by default from now for 90 days. */
    public Flux<Blackout> list(Caller caller, UUID venueId, Instant from, Instant to) {
        var start = from == null ? clock.instant() : from;
        var end = to == null ? start.plus(DEFAULT_LOOKAHEAD) : to;
        if (!end.isAfter(start)) {
            return Flux.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "The range must end after it starts"));
        }
        return VenueAccess.accessible(venues, caller, venueId).thenMany(Flux.defer(() -> blackouts.findByVenue(venueId, start, end)));
    }
}
