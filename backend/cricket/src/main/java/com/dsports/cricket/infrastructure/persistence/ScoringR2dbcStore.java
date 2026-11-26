package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.scoring.CareerStats;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.Shot;
import com.dsports.shared.application.TransactionRunner;
import io.r2dbc.spi.Row;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The scoring log and snapshot in PostgreSQL. {@code exclusive} takes a transaction-scoped advisory lock on the match, so concurrent requests for one match
 * run one after the other; the unique keys on the table are the second line of defence if that lock were ever bypassed.
 */
public class ScoringR2dbcStore implements ScoringStore {

    private static final String EVENT_COLUMNS = "seq, type, payload::text AS payload, request_hash, client_event_id, actor_id, waivers::text AS waivers, created_at, tapped_at";

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

    @Override
    public Flux<CareerStats.Match> statsOf(UUID playerId) {
        return db.sql("SELECT * FROM player_match_stats WHERE player_id = :player ORDER BY match_id").bind("player", playerId).map((row, meta) -> toStats(row)).all();
    }

    @Override
    public Mono<java.util.Map<Integer, Shot>> shots(UUID matchId) {
        return db.sql("SELECT seq, x, y FROM ball_shots WHERE match_id = :match ORDER BY seq").bind("match", matchId)
            .map((row, meta) -> java.util.Map.entry(row.get("seq", Integer.class), new Shot(n(row, "x"), n(row, "y")))).all()
            .collectMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue);
    }

    @Override
    public Flux<UUID> matchIdsToRebuild() {
        return db.sql("SELECT match_id FROM (SELECT match_id FROM match_snapshots WHERE match_status = 'COMPLETED' UNION SELECT match_id FROM player_match_stats) m ORDER BY match_id").map((row, meta) -> row.get("match_id", UUID.class)).all();
    }

    private static int n(Row row, String column) {
        return row.get(column, Number.class).intValue();
    }

    private static CareerStats.Match toStats(Row row) {
        return new CareerStats.Match(row.get("player_id", UUID.class), BallType.valueOf(row.get("ball_type", String.class)), n(row, "innings_batted"), n(row, "runs"), n(row, "balls"),
            n(row, "not_outs"), n(row, "fours"), n(row, "sixes"), n(row, "highest"), Boolean.TRUE.equals(row.get("highest_not_out", Boolean.class)), n(row, "fifties"), n(row, "hundreds"),
            n(row, "ducks"), n(row, "innings_bowled"), n(row, "balls_bowled"), n(row, "runs_conceded"), n(row, "wickets"), n(row, "maidens"), n(row, "best_wickets"), n(row, "best_runs"),
            n(row, "catches"), n(row, "stumpings"), n(row, "run_outs"));
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
        var tapped = row.get("tapped_at", Instant.class);
        return switch (type) {
            case "UNDO" -> LogEntry.undo(seq, codec.voids(payload), client, actor, at).withTappedAt(tapped);
            case "ADMIN_UNLOCK" -> LogEntry.unlock(seq, codec.reason(payload), client, actor, at);
            default -> LogEntry.command(seq, codec.command(ScoreCommand.Type.valueOf(type), payload), client, actor, at, codec.waivers(row.get("waivers", String.class))).withTappedAt(tapped);
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
            var insertSpec = db.sql("""
                    INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, waivers, created_at, tapped_at)
                    VALUES (:id, :match, :seq, :type, CAST(:payload AS jsonb), :schema, :hash, :client, :actor, CAST(:waivers AS jsonb), :at, :tapped)
                    """)
                .bind("id", UUID.randomUUID()).bind("match", matchId).bind("seq", entry.seq()).bind("type", typeOf(entry)).bind("payload", payloadOf(entry))
                .bind("schema", (short) ScoringJsonCodec.SCHEMA_VERSION).bind("hash", requestHash).bind("client", entry.clientEventId()).bind("actor", entry.actorId())
                .bind("waivers", codec.waiversJson(entry.waivers())).bind("at", entry.at());
            var insert = entry.tappedAt() == null ? insertSpec.bindNull("tapped", Instant.class) : insertSpec.bind("tapped", entry.tappedAt());
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

        @Override
        public Mono<Void> addShot(int seq, Shot shot, UUID actorId, Instant at) {
            return db.sql("INSERT INTO ball_shots (match_id, seq, x, y, actor_id, created_at) VALUES (:match, :seq, :x, :y, :actor, :at)")
                .bind("match", matchId).bind("seq", seq).bind("x", (short) shot.x()).bind("y", (short) shot.y()).bind("actor", actorId).bind("at", at).fetch().rowsUpdated().then()
                .onErrorMap(DataIntegrityViolationException.class, e -> new CricketDomainException(CricketErrorCode.SHOT_ALREADY_RECORDED, "The shot for this ball is already recorded"));
        }

        @Override
        public Mono<Void> replaceStats(List<CareerStats.Match> rows) {
            var delete = db.sql("DELETE FROM player_match_stats WHERE match_id = :match").bind("match", matchId).fetch().rowsUpdated().then();
            return delete.then(Flux.fromIterable(rows).concatMap(r -> db.sql("""
                    INSERT INTO player_match_stats (match_id, player_id, ball_type, innings_batted, runs, balls, not_outs, fours, sixes, highest, highest_not_out, fifties, hundreds, ducks,
                        innings_bowled, balls_bowled, runs_conceded, wickets, maidens, best_wickets, best_runs, catches, stumpings, run_outs)
                    VALUES (:match, :player, :ball, :ib, :runs, :balls, :no, :fours, :sixes, :hi, :hino, :f50, :f100, :ducks, :bowled, :bb, :rc, :wk, :md, :bw, :br, :ct, :st, :ro)
                    """).bind("match", matchId).bind("player", r.playerId()).bind("ball", r.ballType().name()).bind("ib", r.inningsBatted()).bind("runs", r.runs())
                .bind("balls", r.balls()).bind("no", r.notOuts()).bind("fours", r.fours()).bind("sixes", r.sixes()).bind("hi", r.highest()).bind("hino", r.highestNotOut())
                .bind("f50", r.fifties()).bind("f100", r.hundreds()).bind("ducks", r.ducks()).bind("bowled", r.inningsBowled()).bind("bb", r.ballsBowled())
                .bind("rc", r.runsConceded()).bind("wk", r.wickets()).bind("md", r.maidens()).bind("bw", r.bestWickets()).bind("br", r.bestRuns()).bind("ct", r.catches())
                .bind("st", r.stumpings()).bind("ro", r.runOuts()).fetch().rowsUpdated()).then());
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
