package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.scoring.CareerStats;
import com.dsports.cricket.domain.scoring.EditWindow;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.LogEntry;
import com.dsports.cricket.domain.scoring.MatchScore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoreOutcome;
import com.dsports.cricket.domain.scoring.ScoringLog;
import com.dsports.shared.application.TransactionRunner;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dsports.cricket.domain.scoring.GoldenMatch.SETUP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V55 and the scoring store on a real PostgreSQL: the log, the snapshot, the unique keys, the append-only trigger and the per-match lock. */
@Tag("integration")
class ScoringPersistenceIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID SCORER = new UUID(9, 9);
    private static DatabaseClient db;
    private static TransactionalOperator TX;
    private static MatchR2dbcRepositoryAdapter matches;
    private static TeamR2dbcRepositoryAdapter teams;
    private static ScoringR2dbcStore store;
    private static final ScoringJsonCodec CODEC = new ScoringJsonCodec();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        var connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        db = DatabaseClient.create(connectionFactory);
        teams = new TeamR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcTeamRepository.class), db);
        matches = new MatchR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcMatchRepository.class), db);
        var operator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        TX = operator;
        TransactionRunner runner = new TransactionRunner() {
            @Override
            public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
                return Mono.defer(work).as(operator::transactional);
            }
        };
        store = new ScoringR2dbcStore(db, runner, CODEC, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static UUID newMatch() {
        var creator = UUID.randomUUID();
        var a = Team.create(creator, "A " + UUID.randomUUID(), "Chennai", NOW);
        var b = Team.create(creator, "B " + UUID.randomUUID(), "Chennai", NOW);
        teams.save(a).block();
        teams.save(b).block();
        var match = Match.create(creator, a.getId(), b.getId(), new Match.Settings(2, BallType.TENNIS, MatchRules.standard(), NOW, "Chennai", null), null, NOW);
        return matches.save(match).block().getId();
    }

    /** Scores the commands the way the use case will: lock, fold the effective log, apply, append the event and the snapshot. */
    private static void score(UUID matchId, List<ScoreCommand> commands) {
        for (var command : commands) {
            scoreOne(matchId, command, UUID.randomUUID(), "h-" + command.type());
        }
    }

    private static void scoreOne(UUID matchId, ScoreCommand command, UUID clientEventId, String hash) {
        store.exclusive(matchId, s -> s.entries().collectList().flatMap(entries -> s.snapshot().map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()).flatMap(snap -> {
            var score = MatchScoring.fold(SETUP, ScoringLog.commands(entries));
            var outcome = (ScoreOutcome.Accepted) MatchScoring.apply(SETUP, score, command);
            int seq = entries.size() + 1;
            var entry = LogEntry.command(seq, command, clientEventId, SCORER, NOW.plusSeconds(seq), outcome.waivers());
            var window = snap.map(ScoringStore.Snapshot::window).orElse(EditWindow.open()).after(outcome.score(), entry.at());
            return s.append(entry, hash, new ScoringStore.Snapshot(seq, outcome.score(), window), outcome.score().status());
        }))).block();
    }

    private static MatchStatus statusOf(UUID matchId) {
        return matches.findById(matchId).block().getStatus();
    }

    // ---- the log and the snapshot -------------------------------------------------------------------------------

    @Test
    void the_golden_match_is_written_and_read_back_event_for_event() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.wholeMatch());

        var entries = store.entries(matchId).collectList().block();

        assertThat(entries).hasSize(GoldenMatch.wholeMatch().size());
        assertThat(entries).extracting(LogEntry::seq).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, entries.size()).boxed().toList());
        assertThat(ScoringLog.commands(entries)).isEqualTo(GoldenMatch.wholeMatch());
        assertThat(entries).allSatisfy(e -> assertThat(e.actorId()).isEqualTo(SCORER));
    }

    @Test
    void the_snapshot_equals_the_fold_of_the_events_and_the_match_status_follows_it() {
        var matchId = newMatch();
        var commands = GoldenMatch.wholeMatch();
        for (int i = 0; i < commands.size(); i++) {
            score(matchId, List.of(commands.get(i)));
            var snapshot = store.snapshot(matchId).block();
            var entries = store.entries(matchId).collectList().block();
            assertThat(snapshot.seq()).as("seq after command %d", i + 1).isEqualTo(entries.get(entries.size() - 1).seq());
            assertThat(snapshot.score()).as("snapshot after command %d", i + 1).isEqualTo(MatchScoring.fold(SETUP, ScoringLog.commands(entries)));
            assertThat(statusOf(matchId)).isEqualTo(snapshot.score().status());
        }
        assertThat(statusOf(matchId)).isEqualTo(MatchStatus.COMPLETED);
    }

    @Test
    void the_result_and_the_correction_window_are_stored_with_the_snapshot() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.wholeMatch());

        var snapshot = store.snapshot(matchId).block();
        assertThat(snapshot.window().firstFinishedAt()).isNotNull();
        assertThat(snapshot.window().editableUntil()).isEqualTo(snapshot.window().firstFinishedAt().plus(EditWindow.LENGTH));
        var stored = db.sql("SELECT match_status, result::text AS result FROM match_snapshots WHERE match_id = :m").bind("m", matchId)
            .map((row, meta) -> row.get("match_status", String.class) + " " + row.get("result", String.class)).one().block();
        assertThat(stored).startsWith("COMPLETED").contains("WON_BY_WICKETS").contains("\"margin\": 10");
    }

    @Test
    void an_undo_and_an_admin_unlock_are_events_too_and_come_back_with_their_details() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.lionsInnings().subList(0, 3));
        var snapshot = store.snapshot(matchId).block();
        var undo = LogEntry.undo(4, List.of(3), UUID.randomUUID(), SCORER, NOW.plusSeconds(10));
        var unlock = LogEntry.unlock(5, "scorer's phone died; \"checked\"", UUID.randomUUID(), new UUID(7, 7), NOW.plusSeconds(20));
        var after = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 2));
        store.exclusive(matchId, s -> s.append(undo, "u", new ScoringStore.Snapshot(4, after, snapshot.window()), after.status())
            .then(s.append(unlock, "k", new ScoringStore.Snapshot(5, after, snapshot.window().unlocked(NOW)), after.status()))).block();

        var entries = store.entries(matchId).collectList().block();

        assertThat(entries.get(3)).isEqualTo(undo);
        assertThat(entries.get(4)).isEqualTo(unlock);
        assertThat(ScoringLog.commands(entries)).hasSize(2);
        assertThat(store.snapshot(matchId).block().window().unlockedUntil()).isEqualTo(NOW.plus(EditWindow.LENGTH));
    }

    @Test
    void an_event_is_found_by_its_client_event_id_with_the_fingerprint_of_its_request() {
        var matchId = newMatch();
        var id = UUID.randomUUID();
        scoreOne(matchId, GoldenMatch.lionsInnings().get(0), id, "fingerprint-1");

        var found = store.exclusive(matchId, s -> s.findByClientEventId(id)).block();
        var missing = store.exclusive(matchId, s -> s.findByClientEventId(UUID.randomUUID())).block();

        assertThat(found.requestHash()).isEqualTo("fingerprint-1");
        assertThat(found.entry().seq()).isEqualTo(1);
        assertThat(found.entry().clientEventId()).isEqualTo(id);
        assertThat(missing).isNull();
    }

    @Test
    void the_waivers_of_an_event_are_stored_with_it() {
        var matchId = newMatch();
        var waiver = new com.dsports.cricket.domain.rules.InningsState.Waiver(9, GoldenMatch.tiger(1), com.dsports.cricket.domain.rules.InningsState.WaiverKind.OVER_LIMIT);
        var first = GoldenMatch.lionsInnings().get(0);
        var score = MatchScoring.fold(SETUP, List.of(first));
        store.exclusive(matchId, s -> s.append(LogEntry.command(1, first, UUID.randomUUID(), SCORER, NOW, List.of(waiver)), "w",
            new ScoringStore.Snapshot(1, score, EditWindow.open()), score.status())).block();

        assertThat(store.entries(matchId).blockFirst().waivers()).containsExactly(waiver);
    }

    // ---- the correction window can be rebuilt from the log -------------------------------------------------------------------------------

    @Test
    void a_deleted_snapshot_is_rebuilt_from_the_log_with_the_same_score_and_the_same_lock_dates() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.wholeMatch());
        var winning = GoldenMatch.tigersChase().get(GoldenMatch.tigersChase().size() - 1);
        var seqBeforeUndo = store.entries(matchId).collectList().block().size();
        var undoAt = NOW.plus(Duration.ofHours(23));
        var recompleteAt = NOW.plus(Duration.ofHours(23)).plus(Duration.ofMinutes(30));
        var unlockAt = NOW.plus(Duration.ofHours(30));
        var original = store.snapshot(matchId).block();
        var undo = LogEntry.undo(seqBeforeUndo + 1, List.of(seqBeforeUndo), UUID.randomUUID(), SCORER, undoAt);
        var afterUndo = MatchScoring.fold(SETUP, GoldenMatch.wholeMatch().subList(0, GoldenMatch.wholeMatch().size() - 1));
        var again = LogEntry.command(seqBeforeUndo + 2, winning, UUID.randomUUID(), SCORER, recompleteAt, List.of());
        var unlock = LogEntry.unlock(seqBeforeUndo + 3, "scorer phone died during the final over", UUID.randomUUID(), new UUID(7, 7), unlockAt);
        store.exclusive(matchId, s -> s.append(undo, "u", new ScoringStore.Snapshot(undo.seq(), afterUndo, original.window().after(afterUndo, undo.at())), afterUndo.status())
            .then(s.append(again, "a", new ScoringStore.Snapshot(again.seq(), original.score(), original.window().after(original.score(), again.at())), MatchStatus.COMPLETED))
            .then(s.append(unlock, "k", new ScoringStore.Snapshot(unlock.seq(), original.score(), original.window().unlocked(unlockAt)), MatchStatus.COMPLETED))).block();
        var stored = store.snapshot(matchId).block();
        assertThat(stored.window().firstFinishedAt()).as("finishing again did not restart the clock").isEqualTo(original.window().firstFinishedAt());
        assertThat(stored.window().unlockedUntil()).isEqualTo(unlockAt.plus(EditWindow.LENGTH));

        db.sql("DELETE FROM match_snapshots WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block();
        assertThat(store.snapshot(matchId).block()).isNull();
        var entries = store.entries(matchId).collectList().block();
        var derived = new ScoringStore.Snapshot(entries.get(entries.size() - 1).seq(), MatchScoring.fold(SETUP, ScoringLog.commands(entries)), ScoringLog.windowOf(SETUP, entries));
        store.exclusive(matchId, s -> s.saveSnapshot(derived, derived.score().status())).block();

        assertThat(store.snapshot(matchId).block()).isEqualTo(stored);
    }

    // ---- the two scorers: unique keys ---------------------------------------------------------------------------

    @Test
    void a_second_event_with_the_same_seq_is_refused_as_a_score_conflict_and_nothing_of_it_is_kept() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.lionsInnings().subList(0, 3));
        var statusBefore = statusOf(matchId);
        var snapshotBefore = store.snapshot(matchId).block();
        var stale = LogEntry.command(3, GoldenMatch.lionsInnings().get(3), UUID.randomUUID(), SCORER, NOW, List.of());
        var other = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 4));

        assertThatThrownBy(() -> store.exclusive(matchId, s -> s.append(stale, "x", new ScoringStore.Snapshot(3, other, EditWindow.open()), MatchStatus.IN_PROGRESS)).block())
            .isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.SCORE_CONFLICT));

        assertThat(store.entries(matchId).collectList().block()).hasSize(3);
        assertThat(store.snapshot(matchId).block()).isEqualTo(snapshotBefore);
        assertThat(statusOf(matchId)).isEqualTo(statusBefore);
    }

    @Test
    void the_same_client_event_id_twice_is_refused_even_with_a_new_seq() {
        var matchId = newMatch();
        var id = UUID.randomUUID();
        scoreOne(matchId, GoldenMatch.lionsInnings().get(0), id, "a");
        var again = LogEntry.command(2, GoldenMatch.lionsInnings().get(1), id, SCORER, NOW, List.of());
        var score = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 2));

        assertThatThrownBy(() -> store.exclusive(matchId, s -> s.append(again, "b", new ScoringStore.Snapshot(2, score, EditWindow.open()), score.status())).block())
            .isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CricketErrorCode.SCORE_CONFLICT));
        assertThat(store.entries(matchId).collectList().block()).hasSize(1);
    }

    @Test
    void the_database_itself_refuses_a_duplicate_seq_a_duplicate_client_id_a_bad_type_and_a_zero_seq() {
        var matchId = newMatch();
        var client = UUID.randomUUID();
        insert(matchId, 1, "DELIVER", client);

        assertThatThrownBy(() -> insert(matchId, 1, "DELIVER", UUID.randomUUID())).hasMessageContaining("uq_ball_events_seq");
        assertThatThrownBy(() -> insert(matchId, 2, "DELIVER", client)).hasMessageContaining("uq_ball_events_client");
        assertThatThrownBy(() -> insert(matchId, 2, "TELEPORT", UUID.randomUUID())).hasMessageContaining("ck_ball_events_type");
        assertThatThrownBy(() -> insert(matchId, 0, "DELIVER", UUID.randomUUID())).hasMessageContaining("ck_ball_events_seq");
        insert(newMatch(), 1, "DELIVER", client);
    }

    private static void insert(UUID matchId, int seq, String type, UUID client) {
        db.sql("""
                INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at)
                VALUES (:id, :m, :seq, :type, '{}'::jsonb, 1, 'h', :client, :actor, :at)
                """)
            .bind("id", UUID.randomUUID()).bind("m", matchId).bind("seq", seq).bind("type", type).bind("client", client).bind("actor", SCORER).bind("at", NOW)
            .fetch().rowsUpdated().block();
    }

    @Test
    void the_log_is_append_only_nothing_can_be_updated_or_deleted() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.lionsInnings().subList(0, 2));

        assertThatThrownBy(() -> db.sql("UPDATE ball_events SET payload = '{}'::jsonb WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM ball_events WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block())
            .hasMessageContaining("append-only");
        assertThat(store.entries(matchId).collectList().block()).hasSize(2);
    }

    @Test
    void a_match_or_a_team_that_has_a_log_cannot_be_deleted_and_nothing_is_lost() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.lionsInnings().subList(0, 3));
        var teamId = matches.findById(matchId).block().getTeamAId();

        assertThatThrownBy(() -> db.sql("DELETE FROM matches WHERE id = :m").bind("m", matchId).fetch().rowsUpdated().block())
            .hasMessageContaining("violates foreign key constraint");
        assertThatThrownBy(() -> db.sql("DELETE FROM teams WHERE id = :t").bind("t", teamId).fetch().rowsUpdated().block())
            .hasMessageContaining("violates foreign key constraint");

        assertThat(store.entries(matchId).collectList().block()).hasSize(3);
        assertThat(store.snapshot(matchId).block().seq()).isEqualTo(3);
        assertThat(matches.findById(matchId).block()).isNotNull();
    }

    @Test
    void no_foreign_key_into_the_log_cascades() {
        var rules = db.sql("""
                SELECT conrelid::regclass::text || ' -> ' || confrelid::regclass::text AS fk, confdeltype::text AS on_delete
                FROM pg_constraint WHERE contype = 'f' AND conrelid IN ('ball_events'::regclass, 'match_snapshots'::regclass)
                """).map((row, meta) -> row.get("fk", String.class) + " " + row.get("on_delete", String.class)).all().collectList().block();

        assertThat(rules).containsExactlyInAnyOrder("ball_events -> matches a", "match_snapshots -> matches a");   // 'a' is NO ACTION: a delete is refused, never cascaded
    }

    /**
     * The row-level trigger does not fire for TRUNCATE, so V56 adds a statement-level one. Each statement runs inside a transaction that is always rolled
     * back, so if the guard ever stopped working no other test would lose its data.
     */
    @Test
    void truncate_of_the_log_is_refused_directly_and_through_cascade() {
        var matchId = newMatch();
        score(matchId, GoldenMatch.lionsInnings().subList(0, 2));

        for (var statement : List.of("TRUNCATE ball_events", "TRUNCATE ball_events, match_snapshots", "TRUNCATE matches CASCADE")) {
            assertThatThrownBy(() -> TX.transactional(db.sql(statement).fetch().rowsUpdated().then(Mono.<Void>error(new IllegalStateException("not refused: " + statement)))).block())
                .as(statement).hasMessageContaining("append-only").hasMessageContaining("TRUNCATE");
        }

        assertThat(store.entries(matchId).collectList().block()).hasSize(2);
    }

    // ---- the lock -----------------------------------------------------------------------------------------------

    @Test
    void two_requests_for_one_match_run_one_after_the_other_and_two_different_matches_do_not_wait_for_each_other() {
        var one = newMatch();
        var two = newMatch();
        var order = Collections.synchronizedList(new ArrayList<String>());
        Mono<Void> first = store.exclusive(one, s -> Mono.fromRunnable(() -> order.add("first start")).then(Mono.delay(Duration.ofMillis(500))).doOnNext(x -> order.add("first end")).then());
        Mono<Void> sameMatch = Mono.delay(Duration.ofMillis(150)).then(store.exclusive(one, s -> Mono.fromRunnable(() -> order.add("second start")).then()));
        Mono<Void> otherMatch = Mono.delay(Duration.ofMillis(150)).then(store.exclusive(two, s -> Mono.fromRunnable(() -> order.add("other start")).then()));

        Mono.when(first.subscribeOn(Schedulers.boundedElastic()), sameMatch.subscribeOn(Schedulers.boundedElastic()), otherMatch.subscribeOn(Schedulers.boundedElastic())).block(Duration.ofSeconds(20));

        assertThat(order.indexOf("first start")).isLessThan(order.indexOf("other start"));
        assertThat(order.indexOf("other start")).as("a different match is not held up").isLessThan(order.indexOf("first end"));
        assertThat(order.indexOf("first end")).as("the same match waits for the lock").isLessThan(order.indexOf("second start"));
    }

    @Test
    void a_failure_inside_the_lock_rolls_everything_back_and_releases_the_lock() {
        var matchId = newMatch();
        var entry = LogEntry.command(1, GoldenMatch.lionsInnings().get(0), UUID.randomUUID(), SCORER, NOW, List.of());
        var score = MatchScoring.fold(SETUP, GoldenMatch.lionsInnings().subList(0, 1));

        assertThatThrownBy(() -> store.exclusive(matchId, s -> s.append(entry, "h", new ScoringStore.Snapshot(1, score, EditWindow.open()), score.status())
            .then(Mono.<Void>error(new IllegalStateException("boom")))).block()).hasMessageContaining("boom");

        assertThat(store.entries(matchId).collectList().block()).isEmpty();
        assertThat(store.snapshot(matchId).block()).isNull();
        assertThat(statusOf(matchId)).isEqualTo(MatchStatus.SCHEDULED);
        scoreOne(matchId, GoldenMatch.lionsInnings().get(0), UUID.randomUUID(), "again");
        assertThat(store.entries(matchId).collectList().block()).hasSize(1);
    }

    // ---- V57: when the scorer says they tapped (display only) ------------------------------------------------------------------

    private static LogEntry dotAt(int seq, Instant tapped) {
        return LogEntry.command(seq, GoldenMatch.lionsInnings().get(seq - 1), UUID.randomUUID(), SCORER, NOW.plusSeconds(seq), List.of()).withTappedAt(tapped);
    }

    private static void appendRaw(UUID matchId, LogEntry entry) {
        store.exclusive(matchId, s -> s.entries().collectList().flatMap(entries -> {
            var score = MatchScoring.fold(SETUP, ScoringLog.commands(entries));
            var accepted = (ScoreOutcome.Accepted) MatchScoring.apply(SETUP, score, entry.command());
            return s.append(entry, "h-" + entry.seq(), new ScoringStore.Snapshot(entry.seq(), accepted.score(), EditWindow.open().after(accepted.score(), entry.at())), accepted.score().status());
        })).block();
    }

    @Test
    void the_time_a_ball_was_tapped_is_stored_with_the_event_read_back_and_optional() {
        var matchId = newMatch();
        appendRaw(matchId, dotAt(1, null));
        appendRaw(matchId, dotAt(2, NOW.plusSeconds(2).minusSeconds(90)));
        appendRaw(matchId, dotAt(3, NOW.plusSeconds(3)));

        var entries = store.entries(matchId).collectList().block();
        assertThat(entries).extracting(LogEntry::tappedAt).containsExactly(null, NOW.plusSeconds(2).minusSeconds(90), NOW.plusSeconds(3));
        assertThat(entries).extracting(LogEntry::at).as("the server's own time is what every rule uses").containsExactly(NOW.plusSeconds(1), NOW.plusSeconds(2), NOW.plusSeconds(3));
        var found = store.exclusive(matchId, s -> s.findByClientEventId(entries.get(1).clientEventId())).block();
        assertThat(found.entry().tappedAt()).isEqualTo(entries.get(1).tappedAt());
    }

    @Test
    void the_database_refuses_a_tapped_time_later_than_when_the_event_was_received() {
        var matchId = newMatch();
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at, tapped_at)
                VALUES (:id, :m, 1, 'DELIVER', '{}'::jsonb, 1, 'h', :client, :actor, :at, :tapped)
                """).bind("id", UUID.randomUUID()).bind("m", matchId).bind("client", UUID.randomUUID()).bind("actor", SCORER).bind("at", NOW).bind("tapped", NOW.plusSeconds(1))
            .fetch().rowsUpdated().block()).hasMessageContaining("ck_ball_events_tapped_at");
        assertThat(store.entries(matchId).collectList().block()).isEmpty();
    }

    @Test
    void an_insert_with_and_without_the_new_column_works_and_the_append_only_guards_still_refuse_update_delete_and_truncate_of_it() {
        var matchId = newMatch();
        insert(matchId, 1, "DELIVER", UUID.randomUUID());   // without tapped_at, as every event written before V57 was
        db.sql("""
                INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at, tapped_at)
                VALUES (:id, :m, 2, 'DELIVER', '{}'::jsonb, 1, 'h', :client, :actor, :at, :tapped)
                """).bind("id", UUID.randomUUID()).bind("m", matchId).bind("client", UUID.randomUUID()).bind("actor", SCORER).bind("at", NOW).bind("tapped", NOW.minusSeconds(60))
            .fetch().rowsUpdated().block();

        assertThatThrownBy(() -> db.sql("UPDATE ball_events SET tapped_at = :t WHERE match_id = :m AND seq = 1").bind("t", NOW).bind("m", matchId).fetch().rowsUpdated().block())
            .as("the new column cannot be edited afterwards either").hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("UPDATE ball_events SET tapped_at = NULL WHERE match_id = :m AND seq = 2").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> db.sql("DELETE FROM ball_events WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        for (var statement : List.of("TRUNCATE ball_events", "TRUNCATE matches CASCADE")) {
            assertThatThrownBy(() -> TX.transactional(db.sql(statement).fetch().rowsUpdated().then(Mono.<Void>error(new IllegalStateException("not refused: " + statement)))).block())
                .as(statement).hasMessageContaining("append-only");
        }
        var rows = db.sql("SELECT seq, tapped_at FROM ball_events WHERE match_id = :m ORDER BY seq").bind("m", matchId)
            .map((row, meta) -> row.get("seq", Integer.class) + ":" + row.get("tapped_at", Instant.class)).all().collectList().block();
        assertThat(rows).containsExactly("1:null", "2:" + NOW.minusSeconds(60));
    }

    @Test
    void a_database_that_already_holds_events_at_v56_upgrades_to_v57_with_them_untouched_and_the_guards_in_place() throws Exception {
        var name = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        var jdbc = POSTGRES.getJdbcUrl().replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
        Flyway.configure().dataSource(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").target("56").load().migrate();
        var factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + name);
        var old = DatabaseClient.create(factory);
        var repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory));
        var oldTeams = new TeamR2dbcRepositoryAdapter(repositories.getRepository(SpringR2dbcTeamRepository.class), old);
        var oldMatches = new MatchR2dbcRepositoryAdapter(repositories.getRepository(SpringR2dbcMatchRepository.class), old);
        var creator = UUID.randomUUID();
        var a = Team.create(creator, "A " + UUID.randomUUID(), "Chennai", NOW);
        var b = Team.create(creator, "B " + UUID.randomUUID(), "Chennai", NOW);
        oldTeams.save(a).block();
        oldTeams.save(b).block();
        var matchId = oldMatches.save(Match.create(creator, a.getId(), b.getId(), new Match.Settings(2, BallType.TENNIS, MatchRules.standard(), NOW, "Chennai", null), null, NOW)).block().getId();
        for (int seq = 1; seq <= 3; seq++) {
            old.sql("""
                    INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at)
                    VALUES (:id, :m, :seq, 'DELIVER', '{}'::jsonb, 1, 'h', :client, :actor, :at)
                    """).bind("id", UUID.randomUUID()).bind("m", matchId).bind("seq", seq).bind("client", UUID.randomUUID()).bind("actor", SCORER).bind("at", NOW)
                .fetch().rowsUpdated().block();
        }
        assertThatThrownBy(() -> old.sql("SELECT tapped_at FROM ball_events").fetch().all().collectList().block()).as("the column does not exist before V57").hasMessageContaining("tapped_at");

        Flyway.configure().dataSource(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();

        var rows = old.sql("SELECT seq, tapped_at, created_at FROM ball_events WHERE match_id = :m ORDER BY seq").bind("m", matchId)
            .map((row, meta) -> row.get("seq", Integer.class) + ":" + row.get("tapped_at", Instant.class) + ":" + row.get("created_at", Instant.class)).all().collectList().block();
        assertThat(rows).containsExactly("1:null:" + NOW, "2:null:" + NOW, "3:null:" + NOW);
        assertThatThrownBy(() -> old.sql("UPDATE ball_events SET payload = '{}'::jsonb WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> old.sql("DELETE FROM ball_events WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        old.sql("""
                INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at, tapped_at)
                VALUES (:id, :m, 4, 'DELIVER', '{}'::jsonb, 1, 'h', :client, :actor, :at, :tapped)
                """).bind("id", UUID.randomUUID()).bind("m", matchId).bind("client", UUID.randomUUID()).bind("actor", SCORER).bind("at", NOW).bind("tapped", NOW.minusSeconds(5))
            .fetch().rowsUpdated().block();
    }

    // ---- V58: career statistics (a projection, rebuildable) --------------------------------------------------------------------------

    private static UUID newPlayer(DatabaseClient client) {
        var id = UUID.randomUUID();
        client.sql("INSERT INTO players (id, name, created_by) VALUES (:id, :name, :by)").bind("id", id).bind("name", "P " + id).bind("by", SCORER).fetch().rowsUpdated().block();
        return id;
    }

    private static CareerStats.Match statsRow(UUID player, BallType type, int runs) {
        return new CareerStats.Match(player, type, 1, runs, 20, 0, 2, 1, runs, false, 0, 0, 0, 1, 12, 18, 2, 1, 2, 18, 1, 0, 1);
    }

    @Test
    void stats_rows_are_written_replaced_and_read_back_exactly_and_a_match_with_no_rows_clears_them() {
        var matchId = newMatch();
        var p1 = newPlayer(db);
        var p2 = newPlayer(db);
        store.exclusive(matchId, session -> session.replaceStats(List.of(statsRow(p1, BallType.TENNIS, 30), statsRow(p2, BallType.TENNIS, 12)))).block();
        assertThat(store.statsOf(p1).collectList().block()).containsExactly(statsRow(p1, BallType.TENNIS, 30));
        store.exclusive(matchId, session -> session.replaceStats(List.of(statsRow(p1, BallType.TENNIS, 31)))).block();
        assertThat(store.statsOf(p1).collectList().block()).as("replaced, not added to").containsExactly(statsRow(p1, BallType.TENNIS, 31));
        assertThat(store.statsOf(p2).collectList().block()).as("the other row of that match went with the replace").isEmpty();
        assertThat(store.matchIdsToRebuild().collectList().block()).contains(matchId);
        store.exclusive(matchId, session -> session.replaceStats(List.of())).block();
        assertThat(store.statsOf(p1).collectList().block()).isEmpty();
        assertThat(store.matchIdsToRebuild().collectList().block()).doesNotContain(matchId);
    }

    @Test
    void the_stats_table_refuses_a_player_or_match_that_does_not_exist_an_unknown_ball_type_and_a_negative_figure() {
        var matchId = newMatch();
        var player = newPlayer(db);
        assertThatThrownBy(() -> store.exclusive(matchId, session -> session.replaceStats(List.of(statsRow(UUID.randomUUID(), BallType.TENNIS, 1)))).block()).as("no such player").isNotNull();
        assertThatThrownBy(() -> store.exclusive(UUID.randomUUID(), session -> session.replaceStats(List.of(statsRow(player, BallType.TENNIS, 1)))).block()).as("no such match").isNotNull();
        assertThatThrownBy(() -> store.exclusive(matchId, session -> session.replaceStats(List.of(statsRow(player, BallType.TENNIS, -1)))).block()).as("negative runs").isNotNull();
        store.exclusive(matchId, session -> session.replaceStats(List.of(statsRow(player, BallType.TENNIS, 5)))).block();
        assertThatThrownBy(() -> db.sql("UPDATE player_match_stats SET ball_type = 'GOLF' WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).as("unknown ball type").isNotNull();
        assertThat(store.statsOf(player).collectList().block()).containsExactly(statsRow(player, BallType.TENNIS, 5));
    }

    @Test
    void a_database_with_events_and_snapshots_at_v57_upgrades_to_v58_with_the_log_untouched_the_guards_in_place_and_an_empty_projection() throws Exception {
        var name = "upgrade58_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        var jdbc = POSTGRES.getJdbcUrl().replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + name + "$1");
        Flyway.configure().dataSource(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").target("57").load().migrate();
        var factory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + name);
        var old = DatabaseClient.create(factory);
        var repositories = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(factory));
        var oldTeams = new TeamR2dbcRepositoryAdapter(repositories.getRepository(SpringR2dbcTeamRepository.class), old);
        var oldMatches = new MatchR2dbcRepositoryAdapter(repositories.getRepository(SpringR2dbcMatchRepository.class), old);
        var creator = UUID.randomUUID();
        var a = Team.create(creator, "A " + UUID.randomUUID(), "Chennai", NOW);
        var b = Team.create(creator, "B " + UUID.randomUUID(), "Chennai", NOW);
        oldTeams.save(a).block();
        oldTeams.save(b).block();
        var matchId = oldMatches.save(Match.create(creator, a.getId(), b.getId(), new Match.Settings(2, BallType.TENNIS, MatchRules.standard(), NOW, "Chennai", null), null, NOW)).block().getId();
        for (int seq = 1; seq <= 3; seq++) {
            old.sql("""
                    INSERT INTO ball_events (id, match_id, seq, type, payload, schema_version, request_hash, client_event_id, actor_id, created_at)
                    VALUES (:id, :m, :seq, 'DELIVER', '{}'::jsonb, 1, 'h', :client, :actor, :at)
                    """).bind("id", UUID.randomUUID()).bind("m", matchId).bind("seq", seq).bind("client", UUID.randomUUID()).bind("actor", SCORER).bind("at", NOW)
                .fetch().rowsUpdated().block();
        }
        assertThatThrownBy(() -> old.sql("SELECT count(*) FROM player_match_stats").fetch().all().collectList().block()).as("the table does not exist before V58").hasMessageContaining("player_match_stats");

        Flyway.configure().dataSource(jdbc, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();

        assertThat(old.sql("SELECT count(*) AS n FROM ball_events WHERE match_id = :m").bind("m", matchId).map((r, m) -> r.get("n", Long.class)).one().block()).isEqualTo(3L);
        assertThat(old.sql("SELECT count(*) AS n FROM player_match_stats").map((r, m) -> r.get("n", Long.class)).one().block()).isZero();
        assertThatThrownBy(() -> old.sql("UPDATE ball_events SET payload = '{}'::jsonb WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> old.sql("DELETE FROM ball_events WHERE match_id = :m").bind("m", matchId).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
    }
}
