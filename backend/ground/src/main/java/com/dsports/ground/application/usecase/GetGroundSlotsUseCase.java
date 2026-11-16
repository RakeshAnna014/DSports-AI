package com.dsports.ground.application.usecase;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.application.port.GroundRepository;
import com.dsports.ground.application.result.SlotResult;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.GroundBlackout;
import com.dsports.ground.domain.model.Slot;
import com.dsports.ground.domain.policy.BookingPolicy;
import com.dsports.ground.domain.policy.SlotCalculator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The slots of one ground on one local calendar day, computed (never stored) from the ground's grid, its active bookings and its
 * blackouts. A HELD booking blocks its slot until the expiry job releases it, exactly as the database constraint does.
 */
public class GetGroundSlotsUseCase {

    private final GroundRepository grounds;
    private final BookingRepository bookings;
    private final BlackoutRepository blackouts;
    private final BookingPolicy policy;
    private final Clock clock;

    public GetGroundSlotsUseCase(GroundRepository grounds, BookingRepository bookings, BlackoutRepository blackouts, BookingPolicy policy, Clock clock) {
        this.grounds = grounds;
        this.bookings = bookings;
        this.blackouts = blackouts;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<List<SlotResult>> execute(UUID groundId, LocalDate date) {
        return grounds.findById(groundId)
            .filter(Ground::isActive)
            .switchIfEmpty(Mono.error(new GroundDomainException(GroundErrorCode.GROUND_NOT_FOUND, "Ground not found: " + groundId)))
            .flatMap(ground -> availability(ground, date).map(list -> list.stream().map(SlotResult::from).toList()));
    }

    /** Shared with the search use case. */
    Mono<List<SlotCalculator.SlotAvailability>> availability(Ground ground, LocalDate date) {
        var slots = ground.slotsOn(date);
        if (slots.isEmpty()) {
            return Mono.just(List.of());
        }
        var from = slots.get(0).start();
        var to = slots.get(slots.size() - 1).end();
        var now = clock.instant();
        return Mono.zip(
                bookings.findOccupiedPeriods(ground.getId(), from, to).collectList(),
                blackouts.findOverlapping(ground.getId(), from, to).map(GroundBlackout::period).collectList())
            .map(t -> SlotCalculator.availability(ground, date, t.getT1(), t.getT2(), now, policy));
    }
}
