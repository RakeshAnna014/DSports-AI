package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.GroundBlackout;
import com.dsports.shared.application.TransactionRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Blackouts (maintenance, private events). A blackout cannot cover a held or confirmed booking: those customers are not silently
 * cancelled; the owner must cancel them first. Adding one takes the same per-ground lock as holding a slot, so a blackout and a
 * booking can never both win the same time.
 */
public class ManageBlackoutsUseCase {

    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofDays(90);

    private final GroundRepository grounds;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final TransactionRunner transactions;
    private final Clock clock;

    public ManageBlackoutsUseCase(GroundRepository grounds, BookingRepository bookings, BlackoutRepository blackouts,
                                  TransactionRunner transactions, Clock clock) {
        this.grounds = grounds;
        this.bookings = bookings;
        this.blackouts = blackouts;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<GroundBlackout> add(UUID ownerId, UUID groundId, Instant start, Instant end, String reason) {
        return transactions.inTransaction(() -> bookings.lockGround(groundId)
            .then(Mono.defer(() -> OwnerGrounds.owned(grounds, ownerId, groundId)))
            .flatMap(ground -> {
                var now = clock.instant();
                var blackout = GroundBlackout.create(groundId, start, end, reason, ownerId, now);
                if (!end.isAfter(now)) {
                    return Mono.<GroundBlackout>error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A blackout must end in the future"));
                }
                return bookings.findOccupiedPeriods(groundId, start, end).count().flatMap(active -> active > 0
                    ? Mono.<GroundBlackout>error(new GroundDomainException(GroundErrorCode.BLACKOUT_CONFLICT,
                        active + " booking(s) overlap this period. Cancel them first, then add the blackout."))
                    : blackouts.save(blackout));
            }));
    }

    /** The ground's blackouts overlapping [from, to); by default from now for 90 days. */
    public Flux<GroundBlackout> list(UUID ownerId, UUID groundId, Instant from, Instant to) {
        var start = from == null ? clock.instant() : from;
        var end = to == null ? start.plus(DEFAULT_LOOKAHEAD) : to;
        if (!end.isAfter(start)) {
            return Flux.error(new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "The range must end after it starts"));
        }
        return OwnerGrounds.owned(grounds, ownerId, groundId).thenMany(Flux.defer(() -> blackouts.findOverlapping(groundId, start, end)));
    }
}
