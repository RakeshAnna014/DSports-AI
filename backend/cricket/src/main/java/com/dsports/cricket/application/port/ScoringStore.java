package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/**
 * Where the scoring log and the match snapshot live. {@code exclusive} runs a piece of work in one transaction that holds the match's scoring lock, so
 * two requests for the same match are handled one after the other; the work reads and appends through the {@link Session}.
 */
public interface ScoringStore {

    <T> Mono<T> exclusive(UUID matchId, Function<Session, Mono<T>> work);

    /** The log, oldest first, without taking the lock (for reading). */
    Flux<LogEntry> entries(UUID matchId);

    /** The snapshot, or empty when nothing has been scored. */
    Mono<Snapshot> snapshot(UUID matchId);

    /** The match's score as stored by the last event, the seq of that event, and the correction window. */
    record Snapshot(int seq, MatchScore score, EditWindow window) {
    }

    /** An event with the fingerprint of the request that made it, to tell a retry from a reused id. */
    record Stored(LogEntry entry, String requestHash) {
    }

    interface Session {

        Mono<Stored> findByClientEventId(UUID clientEventId);

        Flux<LogEntry> entries();

        Mono<Snapshot> snapshot();

        /**
         * Appends the event (its seq must be the next one) and, in the same transaction, replaces the snapshot and the match's status. A stale or duplicate
         * seq or client event id fails with {@code SCORE_CONFLICT}.
         */
        Mono<Void> append(LogEntry entry, String requestHash, Snapshot snapshot, MatchStatus status);

        /** Writes (or replaces) only the snapshot: used to rebuild one that was lost from the log. The log and the match's status are not touched. */
        Mono<Void> saveSnapshot(Snapshot snapshot, MatchStatus status);
    }
}
