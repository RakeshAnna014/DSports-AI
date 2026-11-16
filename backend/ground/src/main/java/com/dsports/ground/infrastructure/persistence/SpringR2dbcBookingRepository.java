package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface SpringR2dbcBookingRepository extends R2dbcRepository<BookingEntity, UUID> {

    Mono<BookingEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Query("SELECT * FROM ground_bookings WHERE ground_id = :groundId AND start_at < :to AND end_at > :from ORDER BY start_at")
    Flux<BookingEntity> findByGroundInRange(UUID groundId, java.time.Instant from, java.time.Instant to);

    @Query("SELECT * FROM ground_bookings WHERE id = :id FOR UPDATE")
    Mono<BookingEntity> findByIdForUpdate(UUID id);

    Flux<BookingEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
