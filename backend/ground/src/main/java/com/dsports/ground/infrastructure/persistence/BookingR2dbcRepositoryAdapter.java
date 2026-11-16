package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.BookingRepository;
import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;
import com.dsports.ground.domain.model.Booking;
import com.dsports.ground.domain.model.BookingStatus;
import com.dsports.ground.domain.model.CancelledBy;
import com.dsports.ground.domain.model.Slot;
import io.r2dbc.spi.R2dbcException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public class BookingR2dbcRepositoryAdapter implements BookingRepository {

    private final SpringR2dbcBookingRepository springRepository;
    private final DatabaseClient databaseClient;

    public BookingR2dbcRepositoryAdapter(SpringR2dbcBookingRepository springRepository, DatabaseClient databaseClient) {
        this.springRepository = springRepository;
        this.databaseClient = databaseClient;
    }

    @Override
    public Mono<Booking> findById(UUID id) {
        return springRepository.findById(id).map(BookingR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Booking> findByUser(UUID userId) {
        return springRepository.findByUserIdOrderByCreatedAtDesc(userId).map(BookingR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Booking> findByGround(UUID groundId, Instant from, Instant to) {
        return springRepository.findByGroundInRange(groundId, from, to).map(BookingR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<UUID> findEndedConfirmedIds(Instant endedBefore, int limit) {
        return databaseClient.sql("SELECT id FROM ground_bookings WHERE status = 'CONFIRMED' AND end_at <= :before ORDER BY end_at LIMIT :limit")
            .bind("before", endedBefore).bind("limit", limit)
            .map((row, meta) -> row.get("id", UUID.class))
            .all();
    }

    @Override
    public Mono<Booking> findByIdForUpdate(UUID id) {
        return springRepository.findByIdForUpdate(id).map(BookingR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<UUID> findLapsedHoldIds(Instant now, int limit) {
        return databaseClient.sql("SELECT id FROM ground_bookings WHERE status = 'HELD' AND held_until <= :now ORDER BY held_until LIMIT :limit")
            .bind("now", now).bind("limit", limit)
            .map((row, meta) -> row.get("id", UUID.class))
            .all();
    }

    @Override
    public Mono<Booking> findByUserAndKey(UUID userId, String idempotencyKey) {
        return springRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(BookingR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<Slot> findOccupiedPeriods(UUID groundId, Instant from, Instant to) {
        return databaseClient.sql("""
                SELECT start_at, end_at FROM ground_bookings
                 WHERE ground_id = :groundId AND status IN ('HELD', 'CONFIRMED') AND start_at < :to AND end_at > :from
                 ORDER BY start_at
                """)
            .bind("groundId", groundId).bind("from", from).bind("to", to)
            .map((row, meta) -> new Slot(row.get("start_at", Instant.class), row.get("end_at", Instant.class)))
            .all();
    }

    @Override
    public Mono<Long> countActiveHolds(UUID userId, Instant now) {
        return databaseClient.sql("SELECT COUNT(*) AS held FROM ground_bookings WHERE user_id = :userId AND status = 'HELD' AND held_until > :now")
            .bind("userId", userId).bind("now", now)
            .map((row, meta) -> row.get("held", Long.class))
            .one()
            .defaultIfEmpty(0L);
    }

    @Override
    public Mono<String> nextBookingNumber() {
        return databaseClient.sql("SELECT nextval('ground_booking_seq') AS n")
            .map((row, meta) -> String.format("GB-%06d", row.get("n", Long.class)))
            .one();
    }

    @Override
    public Mono<Booking> save(Booking booking) {
        return springRepository.save(toEntity(booking))
            .doOnNext(saved -> booking.setVersion(saved.getVersion()))
            .thenReturn(booking)
            .onErrorMap(OptimisticLockingFailureException.class, e -> new GroundDomainException(GroundErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                "Booking " + booking.getBookingNumber() + " was modified concurrently. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e -> mapIntegrityViolation(e))
            .onErrorMap(BookingR2dbcRepositoryAdapter::isLostRace, e -> new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE,
                "Another booking for this time was being made at the same moment. Please choose another slot."));
    }

    @Override
    public Mono<Void> lockGround(UUID groundId) {
        return advisoryLock("ground-slot:" + groundId);
    }

    @Override
    public Mono<Void> lockUser(UUID userId) {
        return advisoryLock("ground-user:" + userId);
    }

    private Mono<Void> advisoryLock(String key) {
        return databaseClient.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", key)
            .fetch().all()
            .then();
    }

    /**
     * True when PostgreSQL aborted this insert because it lost a race for the same slot: a deadlock (40P01) or a serialization failure (40001).
     * Depending on the driver path these arrive as a Spring concurrency exception or as an uncategorised R2DBC exception, so the SQL state is checked
     * down the cause chain.
     */
    static boolean isLostRace(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConcurrencyFailureException) {
                return true;
            }
            if (t instanceof R2dbcException r && ("40P01".equals(r.getSqlState()) || "40001".equals(r.getSqlState()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static GroundDomainException mapIntegrityViolation(DataIntegrityViolationException e) {
        var message = String.valueOf(e.getMessage());
        if (message.contains("no_overlap")) {
            return new GroundDomainException(GroundErrorCode.SLOT_UNAVAILABLE, "That time is no longer available. Please choose another slot.");
        }
        if (message.contains("uq_ground_bookings_user_key")) {
            return new GroundDomainException(GroundErrorCode.IDEMPOTENCY_KEY_REUSED, "A booking with this Idempotency-Key already exists");
        }
        return new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "The booking could not be saved");
    }

    static Booking toDomain(BookingEntity e) {
        return Booking.reconstitute(e.getId(), e.getBookingNumber(), e.getGroundId(), e.getUserId(), e.getStartAt(), e.getEndAt(), e.getPrice(),
            e.getCurrency(), BookingStatus.valueOf(e.getStatus()), e.getPaymentId(), e.getIdempotencyKey(), e.getHeldUntil(), e.getCancelledAt(),
            e.getCancelledBy() == null ? null : CancelledBy.valueOf(e.getCancelledBy()), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    static BookingEntity toEntity(Booking b) {
        var e = new BookingEntity();
        e.setId(b.getId());
        e.setBookingNumber(b.getBookingNumber());
        e.setGroundId(b.getGroundId());
        e.setUserId(b.getUserId());
        e.setStartAt(b.getStartAt());
        e.setEndAt(b.getEndAt());
        e.setPrice(b.getPrice());
        e.setCurrency(b.getCurrency());
        e.setStatus(b.getStatus().name());
        e.setPaymentId(b.getPaymentId());
        e.setIdempotencyKey(b.getIdempotencyKey());
        e.setHeldUntil(b.getHeldUntil());
        e.setCancelledAt(b.getCancelledAt());
        e.setCancelledBy(b.getCancelledBy() == null ? null : b.getCancelledBy().name());
        e.setVersion(b.getVersion());
        e.setCreatedAt(b.getCreatedAt());
        e.setUpdatedAt(b.getUpdatedAt());
        return e;
    }
}
