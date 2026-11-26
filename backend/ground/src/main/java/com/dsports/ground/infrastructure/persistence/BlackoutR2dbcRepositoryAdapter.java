package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.domain.model.Blackout;
import io.r2dbc.spi.Row;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Blackouts are insert-only, so they are written with an explicit insert: an id assigned by the domain would otherwise read as an update. */
public class BlackoutR2dbcRepositoryAdapter implements BlackoutRepository {

    private static final String COLUMNS = "id, venue_id, court_id, start_at, end_at, reason, created_by, created_at";

    private final DatabaseClient databaseClient;
    private final R2dbcEntityTemplate template;

    public BlackoutR2dbcRepositoryAdapter(DatabaseClient databaseClient, R2dbcEntityTemplate template) {
        this.databaseClient = databaseClient;
        this.template = template;
    }

    @Override
    public Flux<Blackout> findOverlapping(UUID venueId, UUID courtId, Instant from, Instant to) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM ground_blackouts WHERE venue_id = :venueId AND (court_id IS NULL OR court_id = :courtId)"
                + " AND start_at < :to AND end_at > :from ORDER BY start_at")
            .bind("venueId", venueId).bind("courtId", courtId).bind("from", from).bind("to", to)
            .map((row, meta) -> toDomain(row))
            .all();
    }

    @Override
    public Flux<Blackout> findByVenue(UUID venueId, Instant from, Instant to) {
        return databaseClient.sql("SELECT " + COLUMNS + " FROM ground_blackouts WHERE venue_id = :venueId AND start_at < :to AND end_at > :from ORDER BY start_at")
            .bind("venueId", venueId).bind("from", from).bind("to", to)
            .map((row, meta) -> toDomain(row))
            .all();
    }

    @Override
    public Mono<Blackout> save(Blackout blackout) {
        var entity = new GroundBlackoutEntity();
        entity.setId(blackout.id());
        entity.setVenueId(blackout.venueId());
        entity.setCourtId(blackout.courtId());
        entity.setStartAt(blackout.startAt());
        entity.setEndAt(blackout.endAt());
        entity.setReason(blackout.reason());
        entity.setCreatedBy(blackout.createdBy());
        entity.setCreatedAt(blackout.createdAt());
        return template.insert(entity).thenReturn(blackout);
    }

    private static Blackout toDomain(Row row) {
        return new Blackout(row.get("id", UUID.class), row.get("venue_id", UUID.class), row.get("court_id", UUID.class), row.get("start_at", Instant.class),
            row.get("end_at", Instant.class), row.get("reason", String.class), row.get("created_by", UUID.class), row.get("created_at", Instant.class));
    }
}
