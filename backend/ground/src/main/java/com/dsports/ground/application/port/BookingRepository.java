package com.dsports.ground.application.port;

import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.Slot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface BookingRepository {

    Mono<Booking> findById(UUID id);

    /**
     * Reads a booking and locks its row until the surrounding transaction ends. Paying, expiring and cancelling a booking all take this lock
     * first, so they are strictly ordered per booking: a hold that is being paid cannot be expired underneath the payment, and vice versa.
     */
    Mono<Booking> findByIdForUpdate(UUID id);

    /** Ids of HELD bookings whose payment window ended before {@code now}, oldest first (candidates for the expiry job; each is re-checked under its row lock). */
    Flux<UUID> findLapsedHoldIds(Instant now, int limit);

    /** The customer's bookings, newest first. */
    Flux<Booking> findByUser(UUID userId);

    /** Bookings of a ground whose period overlaps [from, to), in time order. */
    Flux<Booking> findByGround(UUID groundId, Instant from, Instant to);

    /** Ids of CONFIRMED bookings that ended before {@code endedBefore}, oldest first (candidates to be marked COMPLETED; re-checked under the row lock). */
    Flux<UUID> findEndedConfirmedIds(Instant endedBefore, int limit);

    /** The customer's booking made with this Idempotency-Key, if any. */
    Mono<Booking> findByUserAndKey(UUID userId, String idempotencyKey);

    /** The periods of HELD and CONFIRMED bookings of a ground that overlap [from, to). Expired and cancelled bookings never block a slot. */
    Flux<Slot> findOccupiedPeriods(UUID groundId, Instant from, Instant to);

    /** How many HELD bookings of the customer are still inside their payment window at {@code now}. */
    Mono<Long> countActiveHolds(UUID userId, Instant now);

    Mono<String> nextBookingNumber();

    /**
     * Inserts a new booking or updates an existing one. A violation of the database overlap constraint (or a deadlock caused by a true
     * race for the same slot) is reported as {@code SLOT_UNAVAILABLE}, a reused idempotency key as {@code IDEMPOTENCY_KEY_REUSED}, and a
     * stale version as {@code OPTIMISTIC_LOCKING_CONFLICT}.
     */
    Mono<Booking> save(Booking booking);

    /**
     * Takes a transaction-scoped advisory lock for one ground, so that holding a slot and adding a blackout (and concurrent holds) are
     * serialised per ground. It must be called inside a transaction; it is released at commit or rollback. The database overlap
     * constraint stays as the backstop, but PostgreSQL can abort a loser of a true race with a deadlock error instead of an overlap error.
     */
    Mono<Void> lockGround(UUID groundId);

    /**
     * Takes a transaction-scoped advisory lock for one customer, so that two requests of the same customer cannot both pass the
     * idempotency lookup or the hold limit. Always taken before {@link #lockGround} so the two locks can never deadlock.
     */
    Mono<Void> lockUser(UUID userId);
}
