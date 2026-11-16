package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.BookingResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.shared.application.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Holds a slot for a customer while payment runs, in one transaction:
 * <ol>
 *   <li>the customer lock serialises this customer's requests, so the Idempotency-Key lookup and the hold limit cannot be raced;</li>
 *   <li>a retry with the same key and the same request returns the original booking, the same key for a different request is refused;</li>
 *   <li>at most {@code maxActiveHolds} unpaid holds per customer (409 beyond that);</li>
 *   <li>the ground lock serialises holds and blackouts of that ground; the blackout and overlap checks give a clean 409, and the database
 *       overlap constraint remains the backstop that makes double booking impossible whatever the application does.</li>
 * </ol>
 * The price is a snapshot of the ground's rules at this moment.
 */
public class HoldBookingUseCase {
    private static final Logger log = LoggerFactory.getLogger(HoldBookingUseCase.class);

    public record Command(UUID userId, UUID groundId, Instant startAt, Instant endAt, String idempotencyKey) {
    }

    /** {@code replayed} is true when the Idempotency-Key matched an earlier identical request and nothing new was created. */
    public record Result(BookingResult booking, boolean replayed) {
    }

    private final GroundRepository grounds;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final BookingPolicy policy;
    private final Duration holdDuration;
    private final int maxActiveHolds;
    private final TransactionRunner transactions;
    private final Clock clock;

    public HoldBookingUseCase(GroundRepository grounds, BookingRepository bookings, BlackoutRepository blackouts, BookingPolicy policy,
                              Duration holdDuration, int maxActiveHolds, TransactionRunner transactions, Clock clock) {
        this.grounds = grounds;
        this.bookings = bookings;
        this.blackouts = blackouts;
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
        if (command.startAt() == null || command.endAt() == null || command.groundId() == null) {
            return Mono.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A ground, a start and an end are required"));
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
        return grounds.findById(command.groundId())
            .filter(Ground::isActive)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.GROUND_NOT_FOUND, "Ground not found: " + command.groundId())))
            .flatMap(ground -> bookings.countActiveHolds(command.userId(), now).flatMap(holds -> {
                if (holds >= maxActiveHolds) {
                    return Mono.<Booking>error(new GroundDomainException(GroundErrorCode.HOLD_LIMIT_REACHED,
                        "You already have " + holds + " unpaid slot(s) on hold. Pay for them or let them expire before holding another."));
                }
                return bookings.lockGround(ground.getId()).then(Mono.defer(() -> hold(ground, command, now)));
            }).flatMap(booking -> present(booking, false)));
    }

    private Mono<Booking> hold(Ground ground, Command command, Instant now) {
        // Validates the grid, the booking window and the price before touching the database again.
        var number = bookings.nextBookingNumber();
        return number.flatMap(n -> {
            var booking = Booking.hold(ground, command.userId(), command.startAt(), command.endAt(), n, command.idempotencyKey(), now, holdDuration, policy);
            return blackouts.findOverlapping(ground.getId(), booking.getStartAt(), booking.getEndAt()).hasElements().flatMap(blackedOut -> blackedOut
                ? Mono.<Booking>error(new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "The ground is closed at that time"))
                : bookings.findOccupiedPeriods(ground.getId(), booking.getStartAt(), booking.getEndAt()).hasElements().flatMap(taken -> taken
                    ? Mono.<Booking>error(new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "That time is no longer available. Please choose another slot."))
                    : bookings.save(booking)));
        }).doOnNext(b -> log.info("Slot held: booking {} ground {} {}-{} for user {} until {}", b.getBookingNumber(), b.getGroundId(), b.getStartAt(),
            b.getEndAt(), b.getUserId(), b.getHeldUntil()));
    }

    private Mono<Result> present(Booking booking, boolean replayed) {
        return grounds.findById(booking.getGroundId())
            .map(g -> BookingResult.from(booking, g.getName(), g.getCity()))
            .defaultIfEmpty(BookingResult.from(booking, null, null))
            .map(result -> new Result(result, replayed));
    }

    private static boolean sameRequest(Booking existing, Command command) {
        return existing.getGroundId().equals(command.groundId()) && existing.getStartAt().equals(command.startAt()) && existing.getEndAt().equals(command.endAt());
    }
}
