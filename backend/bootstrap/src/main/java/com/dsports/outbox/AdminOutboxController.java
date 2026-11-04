package com.dsports.outbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Operational view of asynchronous follow-up work: inspect failures and re-queue them. */
@RestController
@RequestMapping(path = "/api/v1/admin/outbox", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin Outbox")
public class AdminOutboxController {

    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "DONE", "FAILED");

    private final DatabaseClient databaseClient;

    public AdminOutboxController(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public record OutboxEventView(UUID id, String type, String aggregateId, String status, int attempts,
                                  String lastError, Instant createdAt, Instant nextAttemptAt, Instant processedAt) {}

    @GetMapping
    @Operation(summary = "List outbox messages by status (default FAILED), newest first, at most 100")
    public Flux<OutboxEventView> list(@RequestParam(defaultValue = "FAILED") String status) {
        if (!STATUSES.contains(status)) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + STATUSES));
        }
        return databaseClient.sql("""
                SELECT id, event_type, aggregate_id, status, attempts, last_error, created_at, next_attempt_at, processed_at
                  FROM outbox_events WHERE status = :status ORDER BY created_at DESC LIMIT 100
                """)
            .bind("status", status)
            .map((row, meta) -> new OutboxEventView(row.get("id", UUID.class), row.get("event_type", String.class),
                row.get("aggregate_id", String.class), row.get("status", String.class), row.get("attempts", Integer.class),
                row.get("last_error", String.class), row.get("created_at", Instant.class),
                row.get("next_attempt_at", Instant.class), row.get("processed_at", Instant.class)))
            .all();
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Re-queue a FAILED message for immediate delivery")
    public Mono<Void> retry(@PathVariable UUID id) {
        return databaseClient.sql("""
                UPDATE outbox_events SET status = 'PENDING', attempts = 0, next_attempt_at = :now
                 WHERE id = :id AND status = 'FAILED'
                """)
            .bind("now", Instant.now())
            .bind("id", id)
            .fetch().rowsUpdated()
            .flatMap(updated -> updated == 0
                ? Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Outbox message " + id + " is not FAILED"))
                : Mono.empty());
    }
}
