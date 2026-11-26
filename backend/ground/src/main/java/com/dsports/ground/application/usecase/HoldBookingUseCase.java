package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.CourtRepository;
import com.dsports.ground.application.port.OwnerEligibilityPort;
import com.dsports.ground.application.port.SportCatalogPort;
import com.dsports.ground.application.port.VenueRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Court;
import com.dsports.ground.domain.model.Venue;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Holds a slot of one court for a customer, for the sport they chose, while payment runs, in one transaction:
 * <ol>
 *   <li>the customer lock serialises this customer's requests, so the Idempotency-Key lookup and the hold limit cannot be raced;</li>
 *   <li>a retry with the same key and the same request returns the original booking, the same key for a different request is refused;</li>
 *   <li>at most {@code maxActiveHolds} unpaid holds per customer (409 beyond that);</li>
 *   <li>the venue lock serialises holds and blackouts of that venue; the blackout and overlap checks give a clean 409, and the database
 *       overlap constraint (per court, whatever the sport) remains the backstop that makes double booking impossible whatever the application does.</li>
 * </ol>
 * The venue must be active and its owner must still hold the ground-owner role (otherwise {@code VENUE_NOT_ACCEPTING_BOOKINGS}); the sport must be
 * offered by the court and be bookable in the catalogue. The price is a snapshot of the court's rules at this moment.
 */
public class HoldBookingUseCase {
    private static final Logger log = LoggerFactory.getLogger(HoldBookingUseCase.class);

    public record Command(UUID userId, UUID courtId, UUID sportId, Instant startAt, Instant endAt, String idempotencyKey) {
    }

    /** {@code replayed} is true when the Idempotency-Key matched an earlier identical request and nothing new was created. */
    public record Result(BookingResult booking, boolean replayed) {
    }

    private final VenueRepository venues;
    private final CourtRepository courts;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final OwnerEligibilityPort owners;
    private final SportCatalogPort sports;
    private final BookingPresenter presenter;
    private final BookingPolicy policy;
    private final Duration holdDuration;
    private final int maxActiveHolds;
    private final TransactionRunner transactions;
    private final Clock clock;

    public HoldBookingUseCase(VenueRepository venues, CourtRepository courts, BookingRepository bookings, BlackoutRepository blackouts,
                              OwnerEligibilityPort owners, SportCatalogPort sports, BookingPresenter presenter, BookingPolicy policy,
                              Duration holdDuration, int maxActiveHolds, TransactionRunner transactions, Clock clock) {
        this.venues = venues;
        this.courts = courts;
        this.bookings = bookings;
        this.blackouts = blackouts;
        this.owners = owners;
        this.sports = sports;
        this.presenter = presenter;
        this.policy = policy;
        this.holdDuration = holdDuration;
        this.maxActiveHolds = maxActiveHolds;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<Result> execute(Command command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            return Mono.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "An Idempotency-Key header is required to book a slot"));
        }
        if (command.startAt() == null || command.endAt() == null || command.courtId() == null || command.sportId() == null) {
            return Mono.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A court, a sport, a start and an end are required"));
        }
        return transactions.inTransaction(() -> bookings.lockUser(command.userId())
            .then(Mono.defer(() -> bookings.findByUserAndKey(command.userId(), command.idempotencyKey())))
            .flatMap(existing -> sameRequest(existing, command)
                ? present(existing, true)
                : Mono.<Result>error(new GroundDomainException(GroundErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key " + command.idempotencyKey() + " was already used for a different booking")))
            .switchIfEmpty(Mono.defer(() -> create(command))));
    }

    private Mono<Result> create(Command command) {
        var now = clock.instant();
        return courts.findById(command.courtId())
            .filter(Court::isActive)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.COURT_NOT_FOUND, "Court not found: " + command.courtId())))
            .flatMap(court -> venues.findById(court.getVenueId())
                .filter(Venue::isActive)
                .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.VENUE_NOT_FOUND, "Venue not found: " + court.getVenueId())))
                .flatMap(venue -> requireAccepting(venue).then(requireBookable(command.sportId()))
                    .then(Mono.defer(() -> bookings.countActiveHolds(command.userId(), now)))
                    .flatMap(holds -> {
                        if (holds >= maxActiveHolds) {
                            return Mono.<Booking>error(new GroundDomainException(GroundErrorCode.HOLD_LIMIT_REACHED,
                                "You already have " + holds + " unpaid slot(s) on hold. Pay for them or let them expire before holding another."));
                        }
                        return bookings.lockVenue(venue.getId()).then(Mono.defer(() -> hold(venue, court, command, now)));
                    })))
            .flatMap(booking -> present(booking, false));
    }

    private Mono<Void> requireAccepting(Venue venue) {
        return owners.isOwner(venue.getOwnerId()).flatMap(isOwner -> isOwner
            ? Mono.<Void>empty()
            : Mono.<Void>error(new GroundDomainException(GroundErrorCode.VENUE_NOT_ACCEPTING_BOOKINGS,
                "This venue is not taking new bookings right now.")));
    }

    private Mono<Void> requireBookable(UUID sportId) {
        return sports.findByIds(List.of(sportId)).next().filter(SportCatalogPort.SportRef::bookable)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.SPORT_NOT_BOOKABLE, "That sport cannot be booked")))
            .then();
    }

    private Mono<Booking> hold(Venue venue, Court court, Command command, Instant now) {
        // Validates the sport, the grid, the booking window and the price before touching the database again.
        return bookings.nextBookingNumber().flatMap(number -> {
            var booking = Booking.hold(venue, court, command.sportId(), command.userId(), command.startAt(), command.endAt(), number,
                command.idempotencyKey(), now, holdDuration, policy);
            return blackouts.findOverlapping(venue.getId(), court.getId(), booking.getStartAt(), booking.getEndAt()).hasElements().flatMap(blackedOut -> blackedOut
                ? Mono.<Booking>error(new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "The court is closed at that time"))
                : bookings.findOccupiedPeriods(court.getId(), booking.getStartAt(), booking.getEndAt()).hasElements().flatMap(taken -> taken
                    ? Mono.<Booking>error(new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "That time is no longer available. Please choose another slot."))
                    : bookings.save(booking)));
        }).doOnNext(b -> log.info("Slot held: booking {} court {} sport {} {}-{} for user {} until {}", b.getBookingNumber(), b.getCourtId(), b.getSportId(),
            b.getStartAt(), b.getEndAt(), b.getUserId(), b.getHeldUntil()));
    }

    private Mono<Result> present(Booking booking, boolean replayed) {
        return presenter.present(booking).map(result -> new Result(result, replayed));
    }

    private static boolean sameRequest(Booking existing, Command command) {
        return existing.getCourtId().equals(command.courtId()) && existing.getSportId().equals(command.sportId())
            && existing.getStartAt().equals(command.startAt()) && existing.getEndAt().equals(command.endAt());
    }
}
