package com.dsports.outbox;

import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Inserts outbox rows on the caller's transactional connection ({@link DatabaseClient} joins the active R2DBC
 * transaction), so the row commits or rolls back together with the business change.
 */
@Component
public class R2dbcOutboxWriter implements OutboxWriter {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public R2dbcOutboxWriter(DatabaseClient databaseClient, ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> enqueue(OutboxMessage message) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(message.payload()))
            .onErrorMap(JsonProcessingException.class, e -> new IllegalStateException("Unserialisable outbox payload", e))
            .flatMap(payload -> {
                var now = Instant.now();
                return databaseClient.sql("""
                        INSERT INTO outbox_events (id, event_type, aggregate_id, payload, status, attempts,
                                                   next_attempt_at, created_at)
                        VALUES (:id, :type, :aggregateId, :payload, 'PENDING', 0, :now, :now)
                        ON CONFLICT (event_type, aggregate_id) DO NOTHING
                        """)
                    .bind("id", UUID.randomUUID())
                    .bind("type", message.type())
                    .bind("aggregateId", message.aggregateId())
                    .bind("payload", payload)
                    .bind("now", now)
                    .fetch()
                    .rowsUpdated()
                    .then();
            });
    }
}
