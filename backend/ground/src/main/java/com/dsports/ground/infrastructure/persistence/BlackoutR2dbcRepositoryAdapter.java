package com.dsports.ground.infrastructure.persistence;

import com.dsports.ground.application.port.BlackoutRepository;
import com.dsports.ground.domain.model.GroundBlackout;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Blackouts are insert-only, so they are written with an explicit insert: an id assigned by the domain would otherwise read as an update. */
public class BlackoutR2dbcRepositoryAdapter implements BlackoutRepository {

    private final DatabaseClient databaseClient;
    private final R2dbcEntityTemplate template;

    public BlackoutR2dbcRepositoryAdapter(DatabaseClient databaseClient, R2dbcEntityTemplate template) {
        this.databaseClient = databaseClient;
        this.template = template;
    }

    @Override
    public Flux<GroundBlackout> findOverlapping(UUID groundId, Instant from, Instant to) {
        return databaseClient.sql("""
                SELECT id, ground_id, start_at, end_at, reason, created_by, created_at FROM ground_blackouts
                 WHERE ground_id = :groundId AND start_at < :to AND end_at > :from
                 ORDER BY start_at
                """)
            .bind("groundId", groundId).bind("from", from).bind("to", to)
            .map((row, meta) -> new GroundBlackout(row.get("id", UUID.class), row.get("ground_id", UUID.class), row.get("start_at", Instant.class),
                row.get("end_at", Instant.class), row.get("reason", String.class), row.get("created_by", UUID.class), row.get("created_at", Instant.class)))
            .all();
    }

    @Override
    public Mono<GroundBlackout> save(GroundBlackout blackout) {
        var entity = new GroundBlackoutEntity();
        entity.setId(blackout.id());
        entity.setGroundId(blackout.groundId());
        entity.setStartAt(blackout.startAt());
        entity.setEndAt(blackout.endAt());
        entity.setReason(blackout.reason());
        entity.setCreatedBy(blackout.createdBy());
        entity.setCreatedAt(blackout.createdAt());
        return template.insert(entity).thenReturn(blackout);
    }
}
