package com.dsports.cricket.application.port;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.scoring.CareerStats;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.Shot;
import com.dsports.cricket.domain.scoring.MatchScore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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

    /** The stored per-match rows of one player (every ball type), for the career. These are a cache of {@link CareerStats#ofMatch} over the log, never a source. */
    Flux<CareerStats.Match> statsOf(UUID playerId);

    /** Where the scorer said each ball went, by the ball's seq (display data only; balls with no shot are simply absent). */
    Mono<java.util.Map<Integer, Shot>> shots(UUID matchId);

    /** The matches a rebuild of the stats has to visit: every completed one, and any that still has stored rows (so a row left by a match that is no longer completed is cleared). */
    Flux<UUID> matchIdsToRebuild();

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

        /** Replaces every stats row of this match with these (none when the match is not completed). Runs in the same transaction as the event that changed the match. */
        /** Records where the ball with this seq went. A second shot for the same ball fails with {@code SHOT_ALREADY_RECORDED}; a seq that is not in the log, with {@code SHOT_NOT_ALLOWED}. */
        Mono<Void> addShot(int seq, Shot shot, UUID actorId, java.time.Instant at);

        Mono<Void> replaceStats(List<CareerStats.Match> rows);
    }
}
