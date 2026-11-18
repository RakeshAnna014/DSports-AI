package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.shared.application.TransactionRunner;
import io.r2dbc.spi.Row;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * The scoring log and snapshot in PostgreSQL. {@code exclusive} takes a transaction-scoped advisory lock on the match, so concurrent requests for one match
 * run one after the other; the unique keys on the table are the second line of defence if that lock were ever bypassed.
 */
public class ScoringR2dbcStore implements ScoringStore {

    private static final String EVENT_COLUMNS = "seq, type, payload::text AS payload, request_hash, client_event_id, actor_id, waivers::text AS waivers, created_at";

    private final DatabaseClient db;
    private final TransactionRunner transactions;
    private final ScoringJsonCodec codec;
    private final Clock clock;

    public ScoringR2dbcStore(DatabaseClient db, TransactionRunner transactions, ScoringJsonCodec codec, Clock clock) {
        this.db = db;
        this.transactions = transactions;
        this.codec = codec;
        this.clock = clock;
    }

    @Override
    public <T> Mono<T> exclusive(UUID matchId, Function<Session, Mono<T>> work) {
        return transactions.inTransaction(() -> db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) AS locked")
            .bind("key", "cricket-score:" + matchId).map((row, meta) -> true).one()
            .then(Mono.defer(() -> work.apply(new DbSession(matchId)))));
    }

    @Override
    public Flux<LogEntry> entries(UUID matchId) {
        return entriesOf(matchId);
    }

    @Override
    public Mono<Snapshot> snapshot(UUID matchId) {
        return snapshotOf(matchId);
    }

    private Flux<LogEntry> entriesOf(UUID matchId) {
        return db.sql("SELECT " + EVENT_COLUMNS + " FROM ball_events WHERE match_id = :match ORDER BY seq").bind("match", matchId)
            .map((row, meta) -> toEntry(row)).all();
    }

    private Mono<Snapshot> snapshotOf(UUID matchId) {
        return db.sql("SELECT seq, score::text AS score, first_finished_at, unlocked_until FROM match_snapshots WHERE match_id = :match").bind("match", matchId)
            .map((row, meta) -> new Snapshot(row.get("seq", Integer.class), codec.score(row.get("score", String.class)),
                new EditWindow(row.get("first_finished_at", Instant.class), row.get("unlocked_until", Instant.class)))).one();
    }

    private LogEntry toEntry(Row row) {
        int seq = row.get("seq", Integer.class);
        var type = row.get("type", String.class);
        var payload = row.get("payload", String.class);
        var client = row.get("client_event_id", UUID.class);
        var actor = row.get("actor_id", UUID.class);
        var at = row.get("created_at", Instant.class);
        return switch (type) {
            case "UNDO" -> LogEntry.undo(seq, codec.voids(payload), client, actor, at);
            case "ADMIN_UNLOCK" -> LogEntry.unlock(seq, codec.reason(payload), client, actor, at);
            default -> LogEntry.command(seq, codec.command(ScoreCommand.Type.valueOf(type), payload), client, actor, at, codec.waivers(row.get("waivers", String.class)));
        };
    }

    private final class DbSession implements Session {

        private final UUID matchId;

        DbSession(UUID matchId) {
            this.matchId = matchId;
        }

        @Override
        public Mono<Stored> findByClientEventId(UUID clientEventId) {
            return db.sql("SELECT " + EVENT_COLUMNS + " FROM ball_events WHERE match_id = :match AND client_event_id = :client")
                .bind("match", matchId).bind("client", clientEventId)
                .map((row, meta) -> new Stored(toEntry(row), row.get("request_hash", String.class).trim())).one();
        }

        @Override
        public Flux<LogEntry> entries() {
            return entriesOf(matchId);
        }

        @Override
        public Mono<Snapshot> snapshot() {
            return snapshotOf(matchId);
        }

        @Override
        public Mono<Void> append(LogEntry entry, String requestHash, Snapshot snapshot, MatchStatus status) {
            var insert = db.sql("""
                    INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, waivers, created_at)
                    VALUES (:id, :match, :seq, :type, CAST(:payload AS jsonb), :schema, :hash, :client, :actor, CAST(:waivers AS jsonb), :at)
                    """)
                .bind("id", UUID.randomUUID()).bind("match", matchId).bind("seq", entry.seq()).bind("type", typeOf(entry)).bind("payload", payloadOf(entry))
                .bind("schema", (short) ScoringJsonCodec.SCHEMA_VERSION).bind("hash", requestHash).bind("client", entry.clientEventId()).bind("actor", entry.actorId())
                .bind("waivers", codec.waiversJson(entry.waivers())).bind("at", entry.at());
            var now = clock.instant();
            var matchStatus = db.sql("UPDATE matches SET status = :status, version = version + 1, updated_at = :now WHERE id = :match")
                .bind("status", status.name()).bind("now", now).bind("match", matchId);
            return insert.fetch().rowsUpdated().then(upsertSnapshot(snapshot, status, now)).then(matchStatus.fetch().rowsUpdated()).then()
                .onErrorMap(DataIntegrityViolationException.class, e -> new CricketDomainException(CricketErrorCode.SCORE_CONFLICT,
                    "Someone else scored at the same moment. Reload the match and try again."));
        }

        @Override
        public Mono<Void> saveSnapshot(Snapshot snapshot, MatchStatus status) {
            return upsertSnapshot(snapshot, status, clock.instant());
        }

        private Mono<Void> upsertSnapshot(Snapshot snapshot, MatchStatus status, Instant now) {
            var result = snapshot.score().result();
            var upsert = db.sql("""
                    INSERT INTO match_snapshots (match_id, seq, match_status, score, result, first_finished_at, unlocked_until, updated_at)
                    VALUES (:match, :seq, :status, CAST(:score AS jsonb), CAST(:result AS jsonb), :first, :unlocked, :now)
                    ON CONFLICT (match_id) DO UPDATE SET seq = EXCLUDED.seq, match_status = EXCLUDED.match_status, score = EXCLUDED.score, result = EXCLUDED.result,
                        first_finished_at = EXCLUDED.first_finished_at, unlocked_until = EXCLUDED.unlocked_until, updated_at = EXCLUDED.updated_at
                    """)
                .bind("match", matchId).bind("seq", snapshot.seq()).bind("status", status.name()).bind("score", codec.scoreJson(snapshot.score()))
                .bind("now", now);
            upsert = result.isPresent() ? upsert.bind("result", codec.resultJson(result.get())) : upsert.bindNull("result", String.class);
            upsert = snapshot.window().firstFinishedAt() == null ? upsert.bindNull("first", Instant.class) : upsert.bind("first", snapshot.window().firstFinishedAt());
            upsert = snapshot.window().unlockedUntil() == null ? upsert.bindNull("unlocked", Instant.class) : upsert.bind("unlocked", snapshot.window().unlockedUntil());
            return upsert.fetch().rowsUpdated().then();
        }
    }

    private static String typeOf(LogEntry entry) {
        return switch (entry.kind()) {
            case COMMAND -> entry.command().type().name();
            case UNDO -> "UNDO";
            case ADMIN_UNLOCK -> "ADMIN_UNLOCK";
        };
    }

    private String payloadOf(LogEntry entry) {
        return switch (entry.kind()) {
            case COMMAND -> codec.commandJson(entry.command());
            case UNDO -> codec.voidsJson(entry.voids());
            case ADMIN_UNLOCK -> codec.reasonJson(entry.reason());
        };
    }
}
