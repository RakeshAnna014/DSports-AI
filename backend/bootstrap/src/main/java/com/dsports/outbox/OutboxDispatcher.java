package com.dsports.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Delivers outbox rows.
 * <ul>
 *   <li>Rows are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease, so several application instances never
 *       process the same row at once, and a row abandoned by a crashed instance is picked up again after the lease.</li>
 *   <li>Failures are retried with exponential backoff; after {@code max-attempts} the row becomes FAILED. Failures are
 *       logged, counted ({@code dsports.outbox.messages{result=retry|failed}}) and listed at
 *       {@code GET /api/v1/admin/outbox}, where they can be re-queued.</li>
 * </ul>
 * Polling runs on a scheduler thread; the reactive chain is subscribed once per tick with an error handler.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final TypeReference<Map<String, String>> PAYLOAD_TYPE = new TypeReference<>() {};

    private final DatabaseClient databaseClient;
    private final OutboxHandlers handlers;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration lease;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public OutboxDispatcher(DatabaseClient databaseClient, OutboxHandlers handlers, ObjectMapper objectMapper,
                            MeterRegistry meterRegistry,
                            @Value("${app.outbox.batch-size:20}") int batchSize,
                            @Value("${app.outbox.max-attempts:8}") int maxAttempts,
                            @Value("${app.outbox.lease:PT5M}") Duration lease) {
        this.databaseClient = databaseClient;
        this.handlers = handlers;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:1000}", initialDelayString = "${app.outbox.initial-delay:5000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dispatchBatch()
            .doFinally(signal -> running.set(false))
            .subscribe(
                processed -> {
                    if (processed > 0) {
                        log.debug("Outbox: processed {} message(s)", processed);
                    }
                },
                error -> log.error("Outbox dispatch cycle failed: {}", error.toString(), error));
    }

    /** Claims and processes one batch. Returns the number of messages handled (successfully or not). */
    public Mono<Long> dispatchBatch() {
        return claimBatch()
            .concatMap(this::process)
            .count();
    }

    private Flux<ClaimedMessage> claimBatch() {
        var now = Instant.now();
        return databaseClient.sql("""
                UPDATE outbox_events
                   SET status = 'PROCESSING', attempts = attempts + 1, next_attempt_at = :leaseUntil
                 WHERE id IN (SELECT id FROM outbox_events
                               WHERE status IN ('PENDING', 'PROCESSING') AND next_attempt_at <= :now
                               ORDER BY created_at
                               LIMIT :limit
                               FOR UPDATE SKIP LOCKED)
                RETURNING id, event_type, aggregate_id, payload, attempts
                """)
            .bind("leaseUntil", now.plus(lease))
            .bind("now", now)
            .bind("limit", batchSize)
            .map((row, meta) -> new ClaimedMessage(
                row.get("id", UUID.class),
                row.get("event_type", String.class),
                row.get("aggregate_id", String.class),
                row.get("payload", String.class),
                row.get("attempts", Integer.class)))
            .all();
    }

    private Mono<Void> process(ClaimedMessage message) {
        return Mono.defer(() -> handlers.handle(message.type(), message.aggregateId(), parse(message.payload())))
            .then(markDone(message))
            .onErrorResume(error -> markFailedAttempt(message, error));
    }

    private Mono<Void> markDone(ClaimedMessage message) {
        meterRegistry.counter("dsports.outbox.messages", "type", message.type(), "result", "done").increment();
        return databaseClient.sql("UPDATE outbox_events SET status = 'DONE', processed_at = :now, last_error = NULL WHERE id = :id")
            .bind("now", Instant.now())
            .bind("id", message.id())
            .fetch().rowsUpdated().then();
    }

    private Mono<Void> markFailedAttempt(ClaimedMessage message, Throwable error) {
        var exhausted = message.attempts() >= maxAttempts;
        var result = exhausted ? "failed" : "retry";
        meterRegistry.counter("dsports.outbox.messages", "type", message.type(), "result", result).increment();
        var errorText = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (exhausted) {
            log.error("Outbox message {} ({} {}) FAILED permanently after {} attempts: {}",
                message.id(), message.type(), message.aggregateId(), message.attempts(), errorText);
        } else {
            log.warn("Outbox message {} ({} {}) attempt {} failed, will retry: {}",
                message.id(), message.type(), message.aggregateId(), message.attempts(), errorText);
        }
        var backoff = Duration.ofSeconds(Math.min(600, (long) Math.pow(2, message.attempts())));
        return databaseClient.sql("""
                UPDATE outbox_events SET status = :status, last_error = :error, next_attempt_at = :next WHERE id = :id
                """)
            .bind("status", exhausted ? "FAILED" : "PENDING")
            .bind("error", errorText)
            .bind("next", Instant.now().plus(backoff))
            .bind("id", message.id())
            .fetch().rowsUpdated().then();
    }

    private Map<String, String> parse(String payload) {
        try {
            return objectMapper.readValue(payload, PAYLOAD_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable outbox payload", e);
        }
    }

    private record ClaimedMessage(UUID id, String type, String aggregateId, String payload, int attempts) {}
}
