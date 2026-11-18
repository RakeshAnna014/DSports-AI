package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.Team;
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
}
