package com.dsports.cricket;

import com.dsports.DSportsApplication;
import com.dsports.cricket.application.port.MatchRepository;
import com.dsports.cricket.application.port.ScoringStore;
import com.dsports.cricket.domain.scoring.MatchScoring;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoringLog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scoring over real HTTP with real authentication, on the whole application against a throwaway PostgreSQL: the hand-scored match from the first ball to the
 * result, who may do what, what a rule violation and a stale or repeated request look like, undo, the 24-hour lock and the administrator's unlock, and above all
 * several scorers hitting one match at the same moment.
 */
@Tag("integration")
@SpringBootTest(classes = DSportsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "app.ground.expiry-initial-delay=PT24H", "app.orders.expiry-initial-delay=PT24H", "app.outbox.initial-delay=PT24H", "app.rate-limit.enabled=false"})
class CricketScoringHttpIntegrationTest {

    private static final String PASSWORD = "Sup3rSecret!pass";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired private WebTestClient web;
    @Autowired private DatabaseClient db;
    @Autowired private ScoringStore store;
    @Autowired private MatchRepository matches;

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private record Account(UUID id, String email, String token) {
    }

    private record Squad(String teamId, List<String> playerIds) {
    }

    private record Resp(int status, Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map(String key) {
            return (Map<String, Object>) body.get(key);
        }
    }

    private Account register(String prefix) {
        var email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        web.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD, "firstName", prefix, "lastName", "Tester")).exchange().expectStatus().isCreated();
        return login(email);
    }

    private Account login(String email) {
        @SuppressWarnings("unchecked")
        Map<String, Object> body = web.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("email", email, "password", PASSWORD)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return new Account(UUID.fromString((String) body.get("userId")), email, (String) body.get("accessToken"));
    }

    private Account administrator() {
        var account = register("admin");
        db.sql("INSERT INTO customer_roles (customer_id, role) VALUES (:id, 'ADMIN')").bind("id", account.id()).fetch().rowsUpdated().block();
        return login(account.email());
    }

    @SuppressWarnings("unchecked")
    private Resp call(HttpMethod method, Account who, String uri, Object body) {
        var spec = web.method(method).uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (who != null) {
            spec = spec.headers(h -> h.setBearerAuth(who.token()));
        }
        var result = (body == null ? spec.exchange() : spec.bodyValue(body).exchange()).returnResult(Map.class);
        return new Resp(result.getStatus().value(), (Map<String, Object>) result.getResponseBody().blockFirst());
    }

    private Resp get(Account who, String uri) {
        return call(HttpMethod.GET, who, uri, null);
    }

    private Resp post(Account who, String uri, Object body) {
        return call(HttpMethod.POST, who, uri, body);
    }

    private Squad squad(Account owner, String teamName, int size) {
        var team = post(owner, "/api/v1/teams", Map.of("name", teamName));
        assertThat(team.status()).isEqualTo(201);
        var teamId = (String) team.body().get("id");
        var ids = new ArrayList<String>();
        for (int i = 0; i < size; i++) {
            var p = post(owner, "/api/v1/players", Map.of("name", teamName + " player " + (i + 1)));
            assertThat(post(owner, "/api/v1/teams/" + teamId + "/members", Map.of("playerId", p.body().get("id"))).status()).isEqualTo(201);
            ids.add((String) p.body().get("id"));
        }
        return new Squad(teamId, ids);
    }

    /** Everyone and everything needed to score one match: an organiser, an added scorer, a team member who cannot score, a stranger, and a match with the toss and XIs done. */
    private record Setup(Account organiser, Account scorer, Account viewer, Account stranger, Squad lions, Squad tigers, String matchId) {
        String scoring() {
            return "/api/v1/matches/" + matchId + "/scoring";
        }
    }

    private Setup setup(int overs) {
        var organiser = register("organiser");
        var scorer = register("scorer");
        var viewer = register("viewer");
        var stranger = register("stranger");
        var lions = squad(organiser, "Lions", 11);
        var tigers = squad(organiser, "Tigers", 11);
        var claim = post(viewer, "/api/v1/players/" + lions.playerIds().get(0) + "/claims", null);
        assertThat(post(organiser, "/api/v1/players/claims/" + claim.body().get("id") + "/approve", null).status()).isEqualTo(200);
        var match = post(organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", overs, "ballType", "TENNIS"));
        assertThat(match.status()).isEqualTo(201);
        var matchId = (String) match.body().get("id");
        assertThat(post(organiser, "/api/v1/matches/" + matchId + "/scorers", Map.of("email", scorer.email())).status()).isEqualTo(201);
        assertThat(post(scorer, "/api/v1/matches/" + matchId + "/toss", Map.of("winnerTeamId", lions.teamId(), "decision", "BAT")).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", lions.teamId(), "playerIds", lions.playerIds())).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, scorer, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", tigers.teamId(), "playerIds", tigers.playerIds())).status()).isEqualTo(200);
        return new Setup(organiser, scorer, viewer, stranger, lions, tigers, matchId);
    }

    private static Map<String, Object> envelope(UUID clientEventId, int expectedSeq, Map<String, Object> command) {
        return Map.of("clientEventId", clientEventId.toString(), "expectedSeq", expectedSeq, "command", command);
    }

    private Resp score(Setup s, Account who, int expectedSeq, Map<String, Object> command) {
        return post(who, s.scoring() + "/commands", envelope(UUID.randomUUID(), expectedSeq, command));
    }

    private Resp undo(Setup s, Account who, int expectedSeq) {
        return post(who, s.scoring() + "/undo", Map.of("clientEventId", UUID.randomUUID().toString(), "expectedSeq", expectedSeq));
    }

    // ---- command bodies -----------------------------------------------------------------------------------------------

    private static Map<String, Object> cmd(String type, Object... keyValues) {
        var m = new LinkedHashMap<String, Object>();
        m.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> delivery(String kind, int runs, String runsType, boolean boundary, Map<String, Object> dismissal) {
        var d = new LinkedHashMap<String, Object>();
        d.put("kind", kind);
        d.put("runs", runs);
        if (runsType != null) {
            d.put("runsType", runsType);
        }
        d.put("boundary", boundary);
        if (dismissal != null) {
            d.put("dismissal", dismissal);
        }
        return d;
    }

    private static Map<String, Object> ball(String kind, int runs, String runsType) {
        return cmd("DELIVER", "delivery", delivery(kind, runs, runsType, false, null));
    }

    private static Map<String, Object> dot() {
        return ball("NORMAL", 0, "BAT");
    }

    private static Map<String, Object> out(int runs, Map<String, Object> dismissal) {
        return cmd("DELIVER", "delivery", delivery("NORMAL", runs, "BAT", false, dismissal));
    }

    /** The hand-scored match of the plan as commands, with the real player ids: Lions make 22/2, Tigers chase 23 and win off the last ball. */
    private static List<Map<String, Object>> goldenMatch(Squad lions, Squad tigers) {
        java.util.function.IntFunction<String> l = n -> lions.playerIds().get(n - 1);
        java.util.function.IntFunction<String> t = n -> tigers.playerIds().get(n - 1);
        return List.of(
            cmd("START_INNINGS", "striker", l.apply(1), "nonStriker", l.apply(2), "bowler", t.apply(1)),
            ball("WIDE", 0, null),
            ball("NORMAL", 1, "BAT"),
            cmd("DELIVER", "delivery", delivery("NO_BALL", 4, "BAT", true, null)),
            ball("NORMAL", 2, "BAT"),
            ball("NORMAL", 1, "BYE"),
            out(1, Map.of("type", "RUN_OUT", "batterOut", l.apply(1), "end", "BATTING_END", "fielderId", t.apply(3))),
            cmd("SELECT_BATTER", "batterId", l.apply(3)),
            out(0, Map.of("type", "BOWLED")),
            cmd("SELECT_BATTER", "batterId", l.apply(4)),
            dot(),
            cmd("SELECT_BOWLER", "bowlerId", t.apply(2)),
            ball("NORMAL", 1, "BAT"),
            ball("NORMAL", 2, "BAT"),
            cmd("RETIRE_BATTER", "batterId", l.apply(4), "retirement", "HURT"),
            cmd("SELECT_BATTER", "batterId", l.apply(5)),
            cmd("CHANGE_BOWLER", "bowlerId", t.apply(3)),
            dot(),
            ball("NORMAL", 1, "BAT"),
            cmd("DELIVER", "delivery", delivery("NORMAL", 6, "BAT", true, null)),
            ball("NORMAL", 1, "BAT"),
            cmd("START_INNINGS", "striker", t.apply(1), "nonStriker", t.apply(2), "bowler", l.apply(1)),
            cmd("DELIVER", "delivery", delivery("NORMAL", 6, "BAT", true, null)),
            cmd("DELIVER", "delivery", delivery("NORMAL", 4, "BAT", true, null)),
            ball("NORMAL", 1, "BAT"), ball("NORMAL", 1, "BAT"),
            cmd("DELIVER", "delivery", delivery("NORMAL", 6, "BAT", true, null)),
            dot(),
            cmd("SELECT_BOWLER", "bowlerId", l.apply(2)),
            ball("WIDE", 0, null), ball("NORMAL", 1, "BAT"), dot(), dot(), dot(), dot(),
            ball("NORMAL", 3, "BAT"));
    }

    private int eventCount(String matchId) {
        return db.sql("SELECT count(*) AS n FROM ball_events WHERE match_id = :m").bind("m", UUID.fromString(matchId)).map((r, m) -> r.get("n", Long.class).intValue()).one().block();
    }

    private void assertSnapshotEqualsFold(String matchId) {
        var id = UUID.fromString(matchId);
        var entries = store.entries(id).collectList().block();
        var snapshot = store.snapshot(id).block();
        var match = matches.findById(id).block();
        assertThat(snapshot.seq()).isEqualTo(entries.get(entries.size() - 1).seq());
        assertThat(snapshot.score()).as("the stored snapshot equals the fold of the stored events")
            .isEqualTo(MatchScoring.fold(MatchSetup.from(match), ScoringLog.commands(entries)));
        assertThat(match.getStatus()).isEqualTo(snapshot.score().status());
    }

    private void assertSeqIsContiguous(String matchId, int expected) {
        var row = db.sql("SELECT count(*) AS n, min(seq) AS lo, max(seq) AS hi, count(DISTINCT client_event_id) AS ids FROM ball_events WHERE match_id = :m")
            .bind("m", UUID.fromString(matchId)).map((r, m) -> List.of(r.get("n", Long.class), (long) r.get("lo", Integer.class), (long) r.get("hi", Integer.class), r.get("ids", Long.class))).one().block();
        assertThat(row).as("count, first seq, last seq, distinct client ids").containsExactly((long) expected, 1L, (long) expected, (long) expected);
    }

    // ---- the whole match ------------------------------------------------------------------------------------------------

    @Test
    void theHandScoredMatchIsScoredOverHttpFromTheFirstBallToTheResultAndTheSnapshotAlwaysEqualsTheLog() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        assertThat(commands).hasSize(36);

        Resp last = null;
        for (int i = 0; i < commands.size(); i++) {
            last = score(s, i % 2 == 0 ? s.organiser() : s.scorer(), i, commands.get(i));
            assertThat(last.status()).as("command %d %s: %s", i + 1, commands.get(i), last.body()).isEqualTo(200);
            assertThat(last.body().get("eventSeq")).isEqualTo(i + 1);
            assertThat(last.body().get("replayed")).isEqualTo(false);
            if (i % 5 == 0) {
                assertSnapshotEqualsFold(s.matchId());
            }
        }

        var view = last.map("view");
        assertThat(view.get("matchStatus")).isEqualTo("COMPLETED");
        assertThat(view.get("seq")).isEqualTo(36);
        assertThat(((Map<?, ?>) view.get("result")).get("text")).isEqualTo("Tigers won by 10 wickets (0 balls left)");
        @SuppressWarnings("unchecked")
        var innings = (List<Map<String, Object>>) view.get("innings");
        assertThat(innings.get(0).get("runs")).isEqualTo(22);
        assertThat(innings.get(1).get("runs")).isEqualTo(23);
        @SuppressWarnings("unchecked")
        var batters = (List<Map<String, Object>>) innings.get(0).get("batters");
        assertThat(batters.get(0).get("howOut")).isEqualTo("run out (Tigers player 3)");
        assertThat(batters.get(2).get("howOut")).isEqualTo("b Tigers player 1");
        assertSeqIsContiguous(s.matchId(), 36);
        assertSnapshotEqualsFold(s.matchId());
        assertThat(get(s.organiser(), "/api/v1/matches/" + s.matchId()).body().get("status")).isEqualTo("COMPLETED");
    }

    private Resp shot(Setup s, Account who, int seq, int x, int y) {
        return post(who, s.scoring() + "/shots", Map.of("seq", seq, "x", x, "y", y));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> shotsOf(Setup s) {
        return (List<Map<String, Object>>) get(null, "/api/v1/matches/" + s.matchId() + "/analytics").body().get("shots");
    }

    @Test
    void aShotIsRecordedAfterABatRunBallDrawnOnlyWhileTheBallIsInForceAndNeverChangesTheScoreOrTheLog() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < 4; i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        assertThat(shotsOf(s)).isEmpty();
        int eventsBefore = eventCount(s.matchId());
        var scoreBefore = get(s.organiser(), s.scoring()).body();

        assertThat(shot(s, s.organiser(), 3, 300, -200).status()).as("seq 3 is a one off the bat").isEqualTo(204);
        assertThat(shot(s, s.organiser(), 3, 0, 0).status()).as("one shot for a ball, never changed").isEqualTo(409);
        assertThat(shot(s, s.organiser(), 2, 0, 0).body().get("code")).as("seq 2 is a wide").isEqualTo("SHOT_NOT_ALLOWED");
        assertThat(shot(s, s.organiser(), 99, 0, 0).body().get("code")).as("no such ball").isEqualTo("SHOT_NOT_ALLOWED");
        assertThat(shot(s, s.organiser(), 3, 800, 800).status()).as("outside the field").isEqualTo(400);
        assertThat(shot(s, s.organiser(), 3, 5000, 0).status()).isEqualTo(400);
        assertThat(shot(s, s.viewer(), 3, 0, 0).status()).as("a member who cannot score").isIn(403, 404);
        assertThat(shot(s, null, 3, 0, 0).status()).isEqualTo(401);

        assertThat(eventCount(s.matchId())).as("the log is untouched").isEqualTo(eventsBefore);
        assertThat(get(s.organiser(), s.scoring()).body().get("stateFingerprint")).isEqualTo(scoreBefore.get("stateFingerprint"));
        var points = shotsOf(s);
        assertThat(points).hasSize(1);
        assertThat(points.get(0)).containsEntry("seq", 3).containsEntry("x", 300).containsEntry("y", -200).containsEntry("runs", 1);
        assertThat(points.get(0).toString()).as("names and numbers only").doesNotContain(s.organiser().id().toString());

        assertThat(undo(s, s.organiser(), 4).status()).isEqualTo(200);
        assertThat(undo(s, s.organiser(), 5).status()).isEqualTo(200);
        assertThat(shotsOf(s)).as("the ball was undone, so its shot is not drawn").isEmpty();
        assertThat(shot(s, s.organiser(), 3, 10, 10).status()).as("and cannot be given another").isIn(409, 422);
        assertThatTheShotTableRefusesEdits(s);
    }

    private void assertThatTheShotTableRefusesEdits(Setup s) {
        var m = UUID.fromString(s.matchId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("UPDATE ball_shots SET x = 0 WHERE match_id = :m").bind("m", m).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("DELETE FROM ball_shots WHERE match_id = :m").bind("m", m).fetch().rowsUpdated().block()).hasMessageContaining("append-only");
    }

    private String statsRows(String matchId) {
        return db.sql("SELECT coalesce(string_agg(t::text, ';' ORDER BY t.player_id), '') AS rows FROM player_match_stats t WHERE t.match_id = :m").bind("m", UUID.fromString(matchId))
            .map((r, m) -> r.get("rows", String.class)).one().block();
    }

    private long statsRowCount(String matchId) {
        return db.sql("SELECT count(*) AS n FROM player_match_stats WHERE match_id = :m").bind("m", UUID.fromString(matchId)).map((r, m) -> r.get("n", Long.class)).one().block();
    }

    @Test
    void careerStatsAppearWhenTheMatchCompletesFollowAnUndoAndAreTheSameWhenRebuiltFromTheLogs() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        var bowler = s.tigers().playerIds().get(0);
        for (int i = 0; i < 35; i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        assertThat(get(null, "/api/v1/players/" + bowler + "/stats").status()).as("nothing for a player with no finished match").isEqualTo(404);
        assertThat(statsRowCount(s.matchId())).isZero();

        assertThat(score(s, s.organiser(), 35, commands.get(35)).status()).isEqualTo(200);
        assertThat(statsRowCount(s.matchId())).as("every player of both elevens").isEqualTo(22);
        assertThat(get(null, "/api/v1/players/" + bowler + "/stats").status()).as("a guest (no account) has no public page however many matches they played").isEqualTo(404);
        assertThat(get(null, "/api/v1/matches/" + s.matchId() + "/scorecard").body().toString()).as("and no link from the public scorecard").doesNotContain(bowler);
        db.sql("UPDATE players SET customer_id = :c WHERE id = :p").bind("c", s.stranger().id()).bind("p", UUID.fromString(bowler)).fetch().rowsUpdated().block();
        var career = get(null, "/api/v1/players/" + bowler + "/stats");
        assertThat(career.status()).as("a player linked to an account has a public page, on by default, and it needs no sign-in").isEqualTo(200);
        assertThat(get(null, "/api/v1/matches/" + s.matchId() + "/scorecard").body().toString()).as("the scorecard links to it").contains(bowler);
        assertThat(career.body().get("name")).isEqualTo("Tigers player 1");
        assertThat(career.body().toString()).as("names and numbers only").doesNotContain(bowler, "phone", "customer");
        @SuppressWarnings("unchecked")
        var line = ((List<Map<String, Object>>) career.body().get("lines")).get(0);
        assertThat(line.get("matches")).isEqualTo(1);
        assertThat(((Map<?, ?>) line.get("bowling")).get("wickets")).isEqualTo(1);
        assertThat(((Map<?, ?>) line.get("bowling")).get("overs")).isEqualTo("1.0");

        assertThat(call(HttpMethod.PUT, s.scorer(), "/api/v1/players/me/public-profile", Map.of("visible", false)).status()).as("an account with no player profile has nothing to hide").isEqualTo(404);
        assertThat(call(HttpMethod.PUT, null, "/api/v1/players/me/public-profile", Map.of("visible", false)).status()).isEqualTo(401);
        assertThat(call(HttpMethod.PUT, s.stranger(), "/api/v1/players/me/public-profile", Map.of()).status()).as("the flag is required").isEqualTo(400);
        var hidden = call(HttpMethod.PUT, s.stranger(), "/api/v1/players/me/public-profile", Map.of("visible", false));
        assertThat(hidden.status()).isEqualTo(200);
        assertThat(hidden.body().get("publicProfile")).isEqualTo(false);
        assertThat(get(null, "/api/v1/players/" + bowler + "/stats").status()).as("hidden is the same 404 as an unknown player").isEqualTo(404);
        assertThat(get(null, "/api/v1/matches/" + s.matchId() + "/scorecard").body().toString()).as("hidden removes the scorecard link, the name stays").doesNotContain(bowler).contains("Tigers player 1");
        assertThat(call(HttpMethod.PUT, s.stranger(), "/api/v1/players/me/public-profile", Map.of("visible", true)).status()).isEqualTo(200);
        assertThat(get(null, "/api/v1/players/" + bowler + "/stats").status()).as("shown again").isEqualTo(200);

        var reopened = undo(s, s.organiser(), 36);
        assertThat(reopened.status()).isEqualTo(200);
        assertThat(statsRowCount(s.matchId())).as("an undo that reopens the match takes its rows away").isZero();
        assertThat(get(null, "/api/v1/players/" + bowler + "/stats").status()).isEqualTo(404);
        assertThat(score(s, s.organiser(), 37, commands.get(35)).status()).isEqualTo(200);
        var incremental = statsRows(s.matchId());
        assertThat(incremental).isNotEmpty();

        var admin = administrator();
        assertThat(post(null, "/api/v1/admin/cricket/stats/rebuild", null).status()).isEqualTo(401);
        assertThat(post(s.organiser(), "/api/v1/admin/cricket/stats/rebuild", null).status()).isEqualTo(403);
        db.sql("DELETE FROM player_match_stats WHERE match_id = :m AND player_id = :p").bind("m", UUID.fromString(s.matchId())).bind("p", UUID.fromString(bowler)).fetch().rowsUpdated().block();
        db.sql("UPDATE player_match_stats SET runs = runs + 7 WHERE match_id = :m").bind("m", UUID.fromString(s.matchId())).fetch().rowsUpdated().block();
        var rebuilt = post(admin, "/api/v1/admin/cricket/stats/rebuild", null);
        assertThat(rebuilt.status()).isEqualTo(200);
        assertThat((Integer) rebuilt.body().get("matchesRebuilt")).isGreaterThanOrEqualTo(1);
        assertThat(statsRows(s.matchId())).as("the rebuild gives exactly the incremental numbers").isEqualTo(incremental);
        assertThat(get(null, "/api/v1/players/" + UUID.randomUUID() + "/stats").status()).as("an unknown player is the same 404").isEqualTo(404);
        assertThat(get(null, "/api/v1/players/not-a-uuid/stats").status()).as("malformed input is a 4xx").isBetween(400, 404);
    }

    // ---- who may do what ------------------------------------------------------------------------------------------------

    @Test
    void scoringRoutesNeedASignInAMemberWhoCannotScoreIsForbiddenAndAStrangerIsToldThereIsNoSuchMatch() {
        var s = setup(2);
        var start = envelope(UUID.randomUUID(), 0, goldenMatch(s.lions(), s.tigers()).get(0));

        assertThat(get(null, s.scoring()).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, null, s.scoring() + "/commands", start).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, null, s.scoring() + "/undo", Map.of("clientEventId", UUID.randomUUID().toString(), "expectedSeq", 0)).status()).isEqualTo(401);

        assertThat(get(s.viewer(), s.scoring()).status()).isEqualTo(403);
        assertThat(call(HttpMethod.POST, s.viewer(), s.scoring() + "/commands", start).status()).isEqualTo(403);
        assertThat(undo(s, s.viewer(), 0).status()).isEqualTo(403);

        assertThat(get(s.stranger(), s.scoring()).status()).isEqualTo(404);
        assertThat(call(HttpMethod.POST, s.stranger(), s.scoring() + "/commands", start).status()).isEqualTo(404);
        assertThat(undo(s, s.stranger(), 0).status()).isEqualTo(404);
        assertThat(get(s.organiser(), "/api/v1/matches/" + UUID.randomUUID() + "/scoring").status()).isEqualTo(404);

        assertThat(get(s.organiser(), s.scoring()).status()).isEqualTo(200);
        assertThat(get(s.scorer(), s.scoring()).status()).isEqualTo(200);
        assertThat(eventCount(s.matchId())).isZero();
        assertThat(post(s.organiser(), "/api/v1/admin/matches/" + s.matchId() + "/scoring/unlock", Map.of("reason", "scorer phone died during the final over")).status()).isEqualTo(403);
        assertThat(post(null, "/api/v1/admin/matches/" + s.matchId() + "/scoring/unlock", Map.of("reason", "scorer phone died during the final over")).status()).isEqualTo(401);
    }

    @Test
    void aMatchWithoutAnInningsStartedShowsTheTossAndNothingElse() {
        var s = setup(2);

        var view = get(s.organiser(), s.scoring()).body();

        assertThat(view.get("seq")).isEqualTo(0);
        assertThat(view.get("matchStatus")).isEqualTo("TOSS_DONE");
        assertThat((List<?>) view.get("innings")).isEmpty();
        assertThat(view.get("matchId")).isEqualTo(s.matchId());
    }

    // ---- what bad requests look like -------------------------------------------------------------------------------------

    @Test
    void aRuleOfCricketIsA422WithItsCodeAndNothingIsWrittenAndMalformedInputIsA4xxNeverA500() {
        var s = setup(2);
        var start = goldenMatch(s.lions(), s.tigers()).get(0);
        assertThat(score(s, s.organiser(), 0, start).status()).isEqualTo(200);

        var tooMany = score(s, s.organiser(), 1, ball("NORMAL", 8, "BAT"));
        assertThat(tooMany.status()).isEqualTo(422);
        assertThat(tooMany.body().get("code")).isEqualTo("SCORING_REJECTED");
        assertThat(tooMany.map("details").get("violation")).isEqualTo("RUNS_OUT_OF_RANGE");
        var notOnAFreeHit = score(s, s.organiser(), 1, cmd("SELECT_BATTER", "batterId", s.lions().playerIds().get(5)));
        assertThat(notOnAFreeHit.map("details").get("violation")).isEqualTo("NO_BATTER_NEEDED");
        assertThat(score(s, s.organiser(), 1, cmd("START_INNINGS", "striker", s.lions().playerIds().get(0), "nonStriker", s.lions().playerIds().get(1), "bowler", s.tigers().playerIds().get(0)))
            .map("details").get("violation")).isEqualTo("PREVIOUS_INNINGS_NOT_COMPLETE");
        assertThat(eventCount(s.matchId())).isEqualTo(1);

        assertThat(score(s, s.organiser(), 1, cmd("TELEPORT")).status()).isEqualTo(400);
        assertThat(score(s, s.organiser(), 1, cmd("START_INNINGS", "striker", s.lions().playerIds().get(0))).status()).isEqualTo(400);
        assertThat(score(s, s.organiser(), 1, cmd("DELIVER")).status()).isEqualTo(400);
        assertThat(score(s, s.organiser(), 1, cmd("END_MATCH", "reason", "TUESDAY")).status()).isEqualTo(400);
        assertThat(score(s, s.organiser(), -1, dot()).status()).isEqualTo(400);
        assertThat(post(s.organiser(), s.scoring() + "/commands", Map.of("expectedSeq", 1, "command", dot())).status()).isEqualTo(400);
        assertThat(post(s.organiser(), s.scoring() + "/commands", Map.of("clientEventId", "not-a-uuid", "expectedSeq", 1, "command", dot())).status()).isEqualTo(400);
        assertThat(post(s.organiser(), s.scoring() + "/commands", "{ not json").status()).isEqualTo(400);
        assertThat(get(s.organiser(), "/api/v1/matches/not-a-uuid/scoring").status()).isEqualTo(400);
        assertThat(eventCount(s.matchId())).isEqualTo(1);
    }

    @Test
    void aStaleSeqIsAConflictThatCarriesTheCurrentScoreAndWhatHappenedSinceAndARetryIsAnsweredAsDone() {
        var s = setup(2);
        var start = goldenMatch(s.lions(), s.tigers()).get(0);
        var startId = UUID.randomUUID();
        assertThat(post(s.organiser(), s.scoring() + "/commands", envelope(startId, 0, start)).status()).isEqualTo(200);
        assertThat(score(s, s.organiser(), 1, dot()).status()).isEqualTo(200);

        var stale = score(s, s.scorer(), 1, ball("NORMAL", 4, "BAT"));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code")).isEqualTo("SCORE_CONFLICT");
        assertThat(stale.map("details").get("currentSeq")).isEqualTo(2);
        assertThat(((Map<?, ?>) stale.map("details").get("view")).get("seq")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        var since = (List<Map<String, Object>>) stale.map("details").get("eventsSince");
        assertThat(since).extracting(e -> e.get("seq")).containsExactly(2);
        assertThat(since.get(0).get("text")).isEqualTo("no run");

        var retry = post(s.organiser(), s.scoring() + "/commands", envelope(startId, 0, start));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("replayed")).isEqualTo(true);
        assertThat(retry.body().get("eventSeq")).isEqualTo(1);
        assertThat(eventCount(s.matchId())).isEqualTo(2);

        var reused = post(s.organiser(), s.scoring() + "/commands", envelope(startId, 2, dot()));
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.body().get("code")).isEqualTo("CLIENT_EVENT_REUSED");
        assertThat(eventCount(s.matchId())).isEqualTo(2);
    }

    // ---- many scorers at the same moment ---------------------------------------------------------------------------------

    private List<Resp> parallel(int n, IntFunction<Resp> job) {
        var pool = Executors.newFixedThreadPool(n);
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Resp>>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return job.apply(index);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var results = new ArrayList<Resp>();
            for (var f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Resp> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }

    @Test
    void twentyRequestsFromTheSameViewAtTheSameMomentLetExactlyOneThroughAndTheRestAreConflicts() {
        var s = setup(20);
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);

        var responses = parallel(20, i -> score(s, i % 2 == 0 ? s.organiser() : s.scorer(), 1, dot()));

        assertThat(count(responses, 200)).isEqualTo(1);
        assertThat(count(responses, 409)).isEqualTo(19);
        assertThat(responses.stream().filter(r -> r.status() == 409)).allSatisfy(r -> assertThat(r.body().get("code")).isEqualTo("SCORE_CONFLICT"));
        assertSeqIsContiguous(s.matchId(), 2);
        assertSnapshotEqualsFold(s.matchId());
    }

    @Test
    void theSameRequestSentTenTimesAtOnceIsCountedOnceAndEveryCallerIsToldItWorked() {
        var s = setup(20);
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);
        var body = envelope(UUID.randomUUID(), 1, ball("NORMAL", 2, "BAT"));

        var responses = parallel(10, i -> post(s.organiser(), s.scoring() + "/commands", body));

        assertThat(count(responses, 200)).isEqualTo(10);
        assertThat(responses.stream().filter(r -> Boolean.TRUE.equals(r.body().get("replayed"))).count()).isEqualTo(9);
        assertThat(responses).allSatisfy(r -> assertThat(r.body().get("eventSeq")).isEqualTo(2));
        assertSeqIsContiguous(s.matchId(), 2);
        assertThat(((Map<?, ?>) ((List<?>) responses.get(0).map("view").get("innings")).get(0)).get("runs")).isEqualTo(2);
    }

    @Test
    void twoScorersScoringTheSameMatchTogetherWithRetriesLeaveAContiguousLogAndAScoreEqualToWhatEachWasTold() throws Exception {
        var s = setup(20);
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);
        int each = 40;
        var accepted = Collections.synchronizedMap(new java.util.HashMap<UUID, Integer>());
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<?>>();
            for (var who : List.of(s.organiser(), s.scorer())) {
                jobs.add(pool.submit(() -> {
                    var view = get(who, s.scoring()).body();
                    int mine = 0;
                    while (mine < each) {
                        var command = nextCommandFor(view);
                        var r = post(who, s.scoring() + "/commands", envelope(UUID.randomUUID(), (Integer) view.get("seq"), command));
                        if (r.status() == 200) {
                            mine++;
                            view = r.map("view");
                        } else if (r.status() == 409 && "SCORE_CONFLICT".equals(r.body().get("code"))) {
                            conflicts.incrementAndGet();
                            view = r.map("details").get("view") instanceof Map<?, ?> v ? castView(v) : get(who, s.scoring()).body();
                        } else {
                            throw new IllegalStateException("Unexpected " + r.status() + " " + r.body());
                        }
                    }
                    accepted.put(who.id(), mine);
                    return null;
                }));
            }
            for (var job : jobs) {
                job.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.values()).containsExactly(each, each);
        assertThat(conflicts.get()).as("two scorers racing must have collided at least once").isGreaterThan(0);
        assertSeqIsContiguous(s.matchId(), 1 + 2 * each);
        assertSnapshotEqualsFold(s.matchId());
        for (var who : List.of(s.organiser(), s.scorer())) {
            long mine = db.sql("SELECT count(*) AS n FROM ball_events WHERE match_id = :m AND actor_id = :a").bind("m", UUID.fromString(s.matchId())).bind("a", who.id())
                .map((r, m) -> r.get("n", Long.class)).one().block();
            assertThat(mine).isEqualTo(who.equals(s.organiser()) ? each + 1 : each);
        }
        var legal = ((Map<?, ?>) ((List<?>) get(s.organiser(), s.scoring()).body().get("innings")).get(0));
        assertThat(legal.get("runs")).isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castView(Map<?, ?> view) {
        return (Map<String, Object>) view;
    }

    /** What a sensible scorer does next, read from the view alone: choose the batter or bowler asked for, otherwise bowl a dot ball. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> nextCommandFor(Map<String, Object> view) {
        var needs = (String) view.get("needs");
        if ("BATTER".equals(needs)) {
            var first = ((List<Map<String, Object>>) view.get("eligibleBatters")).get(0);
            return cmd("SELECT_BATTER", "batterId", ((Map<String, Object>) first.get("player")).get("id"));
        }
        if ("BOWLER".equals(needs)) {
            var next = ((List<Map<String, Object>>) view.get("bowlers")).stream().filter(b -> Boolean.TRUE.equals(b.get("canBowlNext"))).findFirst().orElseThrow();
            return cmd("SELECT_BOWLER", "bowlerId", ((Map<String, Object>) next.get("player")).get("id"));
        }
        return dot();
    }

    @Test
    void tenUndosFromTheSameViewAtOnceTakeOffExactlyOneBallAndAnUndoRacingABallLosesOrWinsCleanly() {
        var s = setup(20);
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);
        for (int i = 1; i <= 3; i++) {
            assertThat(score(s, s.organiser(), i, dot()).status()).isEqualTo(200);
        }

        var undos = parallel(10, i -> undo(s, i % 2 == 0 ? s.organiser() : s.scorer(), 4));
        assertThat(count(undos, 200)).isEqualTo(1);
        assertThat(count(undos, 409)).isEqualTo(9);
        assertSeqIsContiguous(s.matchId(), 5);
        assertSnapshotEqualsFold(s.matchId());
        assertThat(((Map<?, ?>) ((List<?>) undos.stream().filter(r -> r.status() == 200).findFirst().orElseThrow().map("view").get("innings")).get(0)).get("overs")).isEqualTo("0.2");

        var race = parallel(2, i -> i == 0 ? undo(s, s.organiser(), 5) : score(s, s.scorer(), 5, dot()));
        assertThat(count(race, 200)).isEqualTo(1);
        assertThat(count(race, 409)).isEqualTo(1);
        assertSeqIsContiguous(s.matchId(), 6);
        assertSnapshotEqualsFold(s.matchId());
    }

    // ---- undo and the 24-hour lock -------------------------------------------------------------------------------------------

    @Test
    void undoReopensAFinishedMatchAndAMatchFinishedOverADayAgoIsLockedUntilAnAdministratorUnlocksItWithAReason() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < commands.size(); i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var done = get(s.organiser(), s.scoring()).body();
        assertThat(((Map<?, ?>) done.get("canUndo")).get("crossesInnings")).isEqualTo(true);
        assertThat(done.get("locked")).isEqualTo(false);

        var reopened = undo(s, s.organiser(), 36);
        assertThat(reopened.status()).isEqualTo(200);
        assertThat(reopened.map("view").get("matchStatus")).isEqualTo("IN_PROGRESS");
        assertThat(reopened.map("view").get("result")).isNull();
        assertThat(score(s, s.organiser(), 37, commands.get(35)).map("view").get("matchStatus")).isEqualTo("COMPLETED");
        assertSnapshotEqualsFold(s.matchId());

        db.sql("UPDATE match_snapshots SET first_finished_at = now() - interval '25 hours' WHERE match_id = :m").bind("m", UUID.fromString(s.matchId())).fetch().rowsUpdated().block();
        var locked = get(s.organiser(), s.scoring()).body();
        assertThat(locked.get("locked")).isEqualTo(true);
        assertThat(((Map<?, ?>) locked.get("canUndo")).get("blockedReason")).isEqualTo("LOCKED");
        var refused = undo(s, s.organiser(), 38);
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("code")).isEqualTo("MATCH_LOCKED");
        assertThat(score(s, s.scorer(), 38, dot()).body().get("code")).isEqualTo("MATCH_LOCKED");

        var admin = administrator();
        var unlockUrl = "/api/v1/admin/matches/" + s.matchId() + "/scoring/unlock";
        assertThat(post(s.organiser(), unlockUrl, Map.of("reason", "scorer phone died during the final over")).status()).isEqualTo(403);
        assertThat(post(admin, unlockUrl, Map.of("reason", "short")).status()).isEqualTo(400);
        var unlocked = post(admin, unlockUrl, Map.of("reason", "scorer phone died during the final over"));
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(unlocked.map("view").get("locked")).isEqualTo(false);
        assertThat(unlocked.body().get("eventSeq")).isEqualTo(39);
        var again = post(admin, unlockUrl, Map.of("reason", "scorer phone died during the final over"));
        assertThat(again.status()).as("it is not locked any more").isEqualTo(409);
        assertThat(again.body().get("code")).isEqualTo("NOT_LOCKED");

        var corrected = undo(s, s.organiser(), 39);
        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.map("view").get("matchStatus")).isEqualTo("IN_PROGRESS");
        var audit = db.sql("SELECT actor_id, payload::text AS payload FROM ball_events WHERE match_id = :m AND type = 'ADMIN_UNLOCK'").bind("m", UUID.fromString(s.matchId()))
            .map((r, m) -> r.get("actor_id", UUID.class) + " " + r.get("payload", String.class)).all().collectList().block();
        assertThat(audit).hasSize(1).allSatisfy(row -> assertThat(row).startsWith(admin.id().toString()).contains("scorer phone died during the final over"));
        assertSeqIsContiguous(s.matchId(), 40);
        assertSnapshotEqualsFold(s.matchId());
    }

    @Test
    void theLogCannotBeEditedEvenByAnAdministratorWithSql() {
        var s = setup(2);
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("UPDATE ball_events SET actor_id = :a WHERE match_id = :m").bind("a", UUID.randomUUID()).bind("m", UUID.fromString(s.matchId()))
            .fetch().rowsUpdated().block()).hasMessageContaining("append-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> db.sql("DELETE FROM ball_events WHERE match_id = :m").bind("m", UUID.fromString(s.matchId())).fetch().rowsUpdated().block())
            .hasMessageContaining("append-only");
        assertThat(eventCount(s.matchId())).isEqualTo(1);
    }

    // ---- the public scorecard and the live list (no sign-in) ----------------------------------------------------------------

    private String scorecard(Setup s) {
        return "/api/v1/matches/" + s.matchId() + "/scorecard";
    }

    @Test
    void aStrangerReadsAStartedMatchWithoutSigningInAndNotBefore() {
        var s = setup(2);
        var before = get(null, scorecard(s));
        assertThat(before.status()).as("not started yet: the same answer as a match that does not exist").isEqualTo(404);
        assertThat(get(null, "/api/v1/matches/" + UUID.randomUUID() + "/scorecard").status()).isEqualTo(404);
        assertThat(get(null, "/api/v1/matches/not-an-id/scorecard").status()).isEqualTo(400);

        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < 6; i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }

        var card = web.get().uri(scorecard(s)).exchange().expectStatus().isOk().expectHeader().exists("ETag").expectHeader().valueEquals("Cache-Control", "no-cache")
            .expectBody(Map.class).returnResult();
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) card.getResponseBody();
        assertThat(card.getResponseHeaders().getETag()).matches("\"6-[0-9a-f]{16}\"");
        assertThat(body.get("seq")).isEqualTo(6);
        assertThat(body.get("teamA")).isEqualTo("Lions");
        assertThat(body.get("live")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        var innings = (List<Map<String, Object>>) body.get("innings");
        assertThat(innings).hasSize(1);
        assertThat(innings.get(0).get("battingTeam")).isEqualTo("Lions");
        assertThat(innings.get(0).get("runRate")).isNotNull();
        assertThat(card.getResponseBody().toString()).doesNotContain("@", "email", "phone", "booking", "createdBy", "claim", "editableUntil", "canUndo");
    }

    @Test
    void theEtagAnswers304UntilSomethingIsScoredAndAnUndoIsSomethingToo() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < 3; i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var etag = web.get().uri(scorecard(s)).exchange().expectStatus().isOk().returnResult(Map.class).getResponseHeaders().getETag();
        assertThat(etag).startsWith("\"3-");

        web.get().uri(scorecard(s)).header("If-None-Match", etag).exchange().expectStatus().isNotModified().expectHeader().valueEquals("ETag", etag).expectBody().isEmpty();
        web.get().uri(scorecard(s)).header("If-None-Match", "\"2\"").exchange().expectStatus().isOk();

        assertThat(score(s, s.organiser(), 3, commands.get(3)).status()).isEqualTo(200);
        var next = web.get().uri(scorecard(s)).header("If-None-Match", etag).exchange().expectStatus().isOk().returnResult(Map.class).getResponseHeaders().getETag();
        assertThat(next).startsWith("\"4-").isNotEqualTo(etag);

        assertThat(undo(s, s.organiser(), 4).status()).isEqualTo(200);
        var afterUndo = web.get().uri(scorecard(s)).header("If-None-Match", next).exchange().expectStatus().isOk().returnResult(Map.class).getResponseHeaders().getETag();
        assertThat(afterUndo).startsWith("\"5-").isNotEqualTo(next);
    }

    @Test
    void renamingAPlayerChangesTheEtagAndTheNextPollGets200WithTheNewNameAlthoughNothingWasScored() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < 3; i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var first = web.get().uri(scorecard(s)).exchange().expectStatus().isOk().returnResult(Map.class);
        var etag = first.getResponseHeaders().getETag();
        web.get().uri(scorecard(s)).header("If-None-Match", etag).exchange().expectStatus().isNotModified();

        var playerId = s.lions().playerIds().get(0);
        var player = get(s.organiser(), "/api/v1/players/" + playerId);
        assertThat(call(HttpMethod.PUT, s.organiser(), "/api/v1/players/" + playerId,
            Map.of("player", Map.of("name", "Arun Kumar"), "expectedVersion", player.body().get("version"))).status()).isEqualTo(200);

        var poll = web.get().uri(scorecard(s)).header("If-None-Match", etag).exchange().expectStatus().isOk().returnResult(Map.class);
        var renamedTag = poll.getResponseHeaders().getETag();
        assertThat(renamedTag).as("same last event, different tag").startsWith("\"3-").isNotEqualTo(etag);
        assertThat(poll.getResponseBody().blockFirst().toString()).contains("Arun Kumar").doesNotContain("Lions player 1,");
        web.get().uri(scorecard(s)).header("If-None-Match", renamedTag).exchange().expectStatus().isNotModified();

        var team = get(s.organiser(), "/api/v1/teams/" + s.lions().teamId());
        assertThat(call(HttpMethod.PUT, s.organiser(), "/api/v1/teams/" + s.lions().teamId(), Map.of("name", "Lions XI", "expectedVersion", team.body().get("version"))).status()).isEqualTo(200);
        var afterTeam = web.get().uri(scorecard(s)).header("If-None-Match", renamedTag).exchange().expectStatus().isOk().returnResult(Map.class);
        assertThat(afterTeam.getResponseHeaders().getETag()).isNotEqualTo(renamedTag);
        assertThat(afterTeam.getResponseBody().blockFirst().get("teamA")).isEqualTo("Lions XI");
    }

    @Test
    void theScorecardSurvivesAMatchBeingLockedAndShowsTheResult() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < commands.size(); i++) {
            assertThat(score(s, s.organiser(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var body = get(null, scorecard(s));
        assertThat(body.status()).isEqualTo(200);
        assertThat(body.body().get("status")).isEqualTo("COMPLETED");
        assertThat(body.body().get("live")).isEqualTo(false);
        assertThat(((Map<?, ?>) body.body().get("result")).get("text")).isEqualTo("Tigers won by 10 wickets (0 balls left)");
    }

    @Test
    void theLiveListShowsOnlyStartedMatchesBeingPlayedFiltersByCityAndPaginatesWithoutASignIn() {
        var city = "Livecity" + UUID.randomUUID().toString().substring(0, 6);
        var notStarted = matchIn(city);
        var live = matchIn(city);
        var finished = matchIn(city);
        var elsewhere = matchIn("Elsewhere" + UUID.randomUUID().toString().substring(0, 6));
        for (var s : List.of(live, finished, elsewhere)) {
            assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);
        }
        var all = goldenMatch(finished.lions(), finished.tigers());
        for (int i = 1; i < all.size(); i++) {
            assertThat(score(finished, finished.organiser(), i, all.get(i)).status()).isEqualTo(200);
        }

        var here = get(null, "/api/v1/public/matches/live?city=" + city.toUpperCase());
        assertThat(here.status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) (Object) here.body().get("items");
        assertThat(items).extracting(m -> m.get("matchId")).containsExactly(live.matchId());
        assertThat(items.get(0).get("teamA")).isEqualTo("Lions");
        assertThat(items.get(0).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(here.body().get("hasNext")).isEqualTo(false);
        assertThat(here.body().toString()).doesNotContain("@", "createdBy", "booking", "scorer");
        assertThat(notStarted.matchId()).isNotNull();

        var allLive = get(null, "/api/v1/public/matches/live?size=50");
        @SuppressWarnings("unchecked")
        var everyone = (List<Map<String, Object>>) (Object) allLive.body().get("items");
        assertThat(everyone).extracting(m -> m.get("matchId")).contains(live.matchId(), elsewhere.matchId()).doesNotContain(finished.matchId(), notStarted.matchId());

        var paged = get(null, "/api/v1/public/matches/live?size=1&page=0");
        assertThat(((List<?>) paged.body().get("items"))).hasSize(1);
        assertThat(paged.body().get("hasNext")).isEqualTo(true);
    }

    @Test
    void theMatchesPathIsAlwaysMyMatchesWhateverTheQueryStringSays() {
        var s = matchIn("Mine" + UUID.randomUUID().toString().substring(0, 6));

        for (var query : List.of("", "?city=Chennai", "?status=LIVE", "?status=live", "?status=LIVE&status=X", "?status=LIVE,FINISHED")) {
            assertThat(get(null, "/api/v1/matches" + query).status()).as(query).isEqualTo(401);
        }
        var mine = web.get().uri("/api/v1/matches").headers(h -> h.setBearerAuth(s.organiser().token())).exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
        assertThat(mine).as("my matches is a plain list, not a page").hasSize(1);
        var withStatus = web.get().uri("/api/v1/matches?status=LIVE").headers(h -> h.setBearerAuth(s.organiser().token())).exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
        assertThat(withStatus).as("a status parameter changes nothing: still my matches").hasSize(1);
    }

    @Test
    void badListParametersAreRefusedWithA400NeverA500AndNoOtherCricketRouteOpened() {
        for (var query : List.of("size=0", "size=51", "size=abc", "page=-1", "page=x", "page=99999", "city=" + "x".repeat(81))) {
            assertThat(get(null, "/api/v1/public/matches/live?" + query).status()).as(query).isEqualTo(400);
        }
        assertThat(get(null, "/api/v1/public/matches/live?status=DONE&unknown=1").status()).as("unknown parameters are ignored").isEqualTo(200);
        var s = setup(2);
        assertThat(get(null, "/api/v1/matches/" + s.matchId()).status()).isEqualTo(401);
        assertThat(get(null, s.scoring()).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, null, scorecard(s), Map.of()).status()).isEqualTo(401);
        assertThat(call(HttpMethod.DELETE, null, scorecard(s), null).status()).isEqualTo(401);
        assertThat(call(HttpMethod.POST, null, "/api/v1/public/matches/live", Map.of()).status()).isEqualTo(401);
    }

    /** A match between two fresh squads with the toss and XIs done and its city set at creation. */
    private Setup matchIn(String city) {
        var organiser = register("organiser");
        var scorer = register("scorer");
        var viewer = register("viewer");
        var stranger = register("stranger");
        var lions = squad(organiser, "Lions", 11);
        var tigers = squad(organiser, "Tigers", 11);
        var match = post(organiser, "/api/v1/matches", Map.of("teamAId", lions.teamId(), "teamBId", tigers.teamId(), "overs", 2, "ballType", "TENNIS", "city", city));
        assertThat(match.status()).isEqualTo(201);
        var matchId = (String) match.body().get("id");
        assertThat(post(organiser, "/api/v1/matches/" + matchId + "/toss", Map.of("winnerTeamId", lions.teamId(), "decision", "BAT")).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, organiser, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", lions.teamId(), "playerIds", lions.playerIds())).status()).isEqualTo(200);
        assertThat(call(HttpMethod.PUT, organiser, "/api/v1/matches/" + matchId + "/playing-xi", Map.of("teamId", tigers.teamId(), "playerIds", tigers.playerIds())).status()).isEqualTo(200);
        return new Setup(organiser, scorer, viewer, stranger, lions, tigers, matchId);
    }

    /** The ids a phone makes without crypto.randomUUID (random bytes with the version 4 and variant bits forced on): the extreme byte values still pass the validation. */
    @Test
    void anIdMadeByThePhonesFallbackIsAccepted() {
        var s = setup(2);
        var allOnes = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");
        var allZeros = UUID.fromString("00000000-0000-4000-8000-000000000000");

        assertThat(post(s.organiser(), s.scoring() + "/commands", envelope(allOnes, 0, goldenMatch(s.lions(), s.tigers()).get(0))).status()).isEqualTo(200);
        assertThat(post(s.organiser(), s.scoring() + "/commands", envelope(allZeros, 1, dot())).status()).isEqualTo(200);
        var again = post(s.organiser(), s.scoring() + "/commands", envelope(allZeros, 1, dot()));

        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("replayed")).isEqualTo(true);
        assertThat(eventCount(s.matchId())).isEqualTo(2);
    }

    // ---- offline batches (step 2f-3): what the server does with the balls a phone queued while it had no signal ---------------------------------

    /** One queued item as the phone will send it: where it goes and what it carries. The id is made when the ball is tapped and never changes. */
    private record Item(String path, UUID clientEventId, int expectedSeq, Map<String, Object> body) {
        Item at(int newExpectedSeq) {
            var copy = new LinkedHashMap<>(body);
            copy.put("expectedSeq", newExpectedSeq);
            return new Item(path, clientEventId, newExpectedSeq, copy);
        }
    }

    private static Item commandItem(Setup s, int expectedSeq, Map<String, Object> command, String tappedAt) {
        var id = UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>(envelope(id, expectedSeq, command));
        if (tappedAt != null) {
            body.put("tappedAt", tappedAt);
        }
        return new Item(s.scoring() + "/commands", id, expectedSeq, body);
    }

    private static Item undoItem(Setup s, int expectedSeq, String mode, String tappedAt) {
        var id = UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>(Map.of("clientEventId", id.toString(), "expectedSeq", expectedSeq, "mode", mode));
        if (tappedAt != null) {
            body.put("tappedAt", tappedAt);
        }
        return new Item(s.scoring() + "/undo", id, expectedSeq, body);
    }

    private Resp send(Account who, Item item) {
        return post(who, item.path(), item.body());
    }

    /** 60 items: the golden match (36) and then 12 corrections, each an undo of the last command followed by the same command again. */
    private static List<Item> sixtyItems(Setup s, String tappedAt) {
        var commands = goldenMatch(s.lions(), s.tigers());
        var items = new ArrayList<Item>();
        for (var command : commands) {
            items.add(commandItem(s, items.size(), command, tappedAt));
        }
        var last = commands.get(commands.size() - 1);
        for (int i = 0; i < 12; i++) {
            items.add(undoItem(s, items.size(), "COMMAND", tappedAt));
            items.add(commandItem(s, items.size(), last, tappedAt));
        }
        assertThat(items).hasSize(60);
        return items;
    }

    private Map<String, Object> engineState(Setup s, Account who) {
        var r = get(who, s.scoring() + "/engine-state");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    @Test
    void sixtyQueuedItemsSentInOrderGiveExactlyTheExpectedLogAndTheServerAndThePhoneCanCheckTheyAgree() {
        var s = setup(2);
        var items = sixtyItems(s, java.time.Instant.now().minusSeconds(3600).toString());
        for (int i = 0; i < items.size(); i++) {
            var r = send(s.scorer(), items.get(i));
            assertThat(r.status()).as("item %d", i + 1).isEqualTo(200);
            assertThat(r.body().get("replayed")).isEqualTo(false);
            assertThat(r.body().get("eventSeq")).isEqualTo(i + 1);
        }
        assertSeqIsContiguous(s.matchId(), 60);
        assertSnapshotEqualsFold(s.matchId());

        var id = UUID.fromString(s.matchId());
        var entries = store.entries(id).collectList().block();
        var expectedKinds = new ArrayList<String>(java.util.Collections.nCopies(36, "COMMAND"));
        for (int i = 0; i < 12; i++) {
            expectedKinds.add("UNDO");
            expectedKinds.add("COMMAND");
        }
        assertThat(entries.stream().map(e -> e.kind().name()).toList()).isEqualTo(expectedKinds);
        assertThat(entries.stream().map(e -> e.clientEventId()).toList()).isEqualTo(items.stream().map(Item::clientEventId).toList());
        assertThat(entries).allSatisfy(e -> assertThat(e.tappedAt()).isNotNull().isBeforeOrEqualTo(e.at()));

        var fold = MatchScoring.fold(MatchSetup.from(matches.findById(id).block()), ScoringLog.commands(entries));
        var view = get(s.scorer(), s.scoring()).body();
        assertThat(view.get("stateFingerprint")).isEqualTo(com.dsports.cricket.domain.scoring.StateFingerprint.of(fold));
        assertThat(view.get("engineVersion")).isEqualTo(1);
        var state = engineState(s, s.scorer());
        assertThat(state.get("stateFingerprint")).isEqualTo(view.get("stateFingerprint"));
        assertThat(state.get("seq")).isEqualTo(60);
        assertThat((List<?>) state.get("entries")).hasSize(60);
        @SuppressWarnings("unchecked")
        var firstEntry = (Map<String, Object>) ((List<?>) state.get("entries")).get(0);
        assertThat(firstEntry).containsEntry("mine", true).containsEntry("clientEventId", items.get(0).clientEventId().toString());
    }

    @Test
    void theSameBatchSentTwiceAtOnceByTwoTabsLeavesOneSetOfEventsAndEveryAnswerIsConsistent() throws Exception {
        var s = setup(2);
        var items = sixtyItems(s, null);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<Resp>>> runs = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    var answers = new ArrayList<Resp>();
                    for (var item : items) {
                        answers.add(send(s.scorer(), item));
                    }
                    return answers;
                }));
            }
            start.countDown();
            var a = runs.get(0).get(120, TimeUnit.SECONDS);
            var b = runs.get(1).get(120, TimeUnit.SECONDS);
            for (int i = 0; i < items.size(); i++) {
                assertThat(a.get(i).status()).as("tab 1, item %d", i + 1).isEqualTo(200);
                assertThat(b.get(i).status()).as("tab 2, item %d", i + 1).isEqualTo(200);
                assertThat(a.get(i).body().get("eventSeq")).as("both tabs are told the same seq for item %d", i + 1).isEqualTo(i + 1).isEqualTo(b.get(i).body().get("eventSeq"));
                boolean oneApplied = !Boolean.TRUE.equals(a.get(i).body().get("replayed")) ^ !Boolean.TRUE.equals(b.get(i).body().get("replayed"));
                assertThat(oneApplied).as("item %d was applied by exactly one tab and replayed to the other", i + 1).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        assertSeqIsContiguous(s.matchId(), 60);
        assertSnapshotEqualsFold(s.matchId());
    }

    @Test
    void whenTheServerAppliedTwentyItemsAndTheAnswersWereLostResendingFromTheStartReplaysThemAndThenTakesTheNewOnes() {
        var s = setup(2);
        var items = sixtyItems(s, null);
        for (int i = 0; i < 20; i++) {
            assertThat(send(s.scorer(), items.get(i)).status()).isEqualTo(200);   // the phone never saw these answers
        }
        for (int i = 0; i < 40; i++) {
            var r = send(s.scorer(), items.get(i));
            assertThat(r.status()).as("item %d", i + 1).isEqualTo(200);
            assertThat(r.body().get("replayed")).as("item %d is a replay only if the server already had it", i + 1).isEqualTo(i < 20);
            assertThat(r.body().get("eventSeq")).isEqualTo(i + 1);
        }
        assertThat(eventCount(s.matchId())).isEqualTo(40);
        assertSeqIsContiguous(s.matchId(), 40);
    }

    @Test
    void aSecondScorerScoringMidwayGivesTheNextItemAConflictThatWritesNothingAndARebasedItemWithTheSameIdThenGoesIn() {
        var s = setup(2);
        var items = sixtyItems(s, null);
        for (int i = 0; i < 5; i++) {
            assertThat(send(s.scorer(), items.get(i)).status()).isEqualTo(200);
        }
        // The organiser scores three wides (they change neither the strike nor the over, so the phone's later balls still apply).
        for (int i = 0; i < 3; i++) {
            assertThat(score(s, s.organiser(), 5 + i, ball("WIDE", 0, null)).status()).isEqualTo(200);
        }
        var refused = send(s.scorer(), items.get(5));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("code")).isEqualTo("SCORE_CONFLICT");
        assertThat(eventCount(s.matchId())).as("nothing was written for the refused item").isEqualTo(8);
        @SuppressWarnings("unchecked")
        var details = (Map<String, Object>) refused.body().get("details");
        assertThat(details.get("currentSeq")).isEqualTo(8);
        @SuppressWarnings("unchecked")
        var since = (List<Map<String, Object>>) details.get("eventsSince");
        assertThat(since).extracting(e -> e.get("seq")).containsExactly(6, 7, 8);
        assertThat(since).allSatisfy(e -> assertThat(e).containsEntry("mine", false).containsEntry("clientEventId", null));
        assertThat(((Map<?, ?>) details.get("view")).get("stateFingerprint")).isNotNull();

        // The scorer chooses "add my balls after theirs": same ids, new expectedSeq. The server checks each one again.
        for (int i = 5; i < 10; i++) {
            var rebased = items.get(i).at(i + 3);
            var r = send(s.scorer(), rebased);
            assertThat(r.status()).as("rebased item %d", i + 1).isEqualTo(200);
            assertThat(r.body().get("eventSeq")).isEqualTo(i + 4);
            assertThat(r.body().get("replayed")).isEqualTo(false);
        }
        assertSeqIsContiguous(s.matchId(), 13);
        assertSnapshotEqualsFold(s.matchId());
        var entries = store.entries(UUID.fromString(s.matchId())).collectList().block();
        assertThat(entries.subList(8, 13).stream().map(e -> e.clientEventId()).toList()).isEqualTo(items.subList(5, 10).stream().map(Item::clientEventId).toList());
    }

    @Test
    void theDeadlinePassingInTheMiddleOfAFlushLetsTheEarlierItemsInRefusesTheRestAsLockedWritesNoGapAndAnUnlockLetsThemInWithTheirOriginalIds() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < commands.size(); i++) {
            assertThat(score(s, s.scorer(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var last = commands.get(commands.size() - 1);
        var queued = List.of(undoItem(s, 36, "COMMAND", null), commandItem(s, 37, last, null), undoItem(s, 38, "COMMAND", null), commandItem(s, 39, last, null));
        assertThat(send(s.scorer(), queued.get(0)).status()).isEqualTo(200);
        assertThat(send(s.scorer(), queued.get(1)).status()).isEqualTo(200);

        db.sql("UPDATE match_snapshots SET first_finished_at = now() - interval '25 hours' WHERE match_id = :m").bind("m", UUID.fromString(s.matchId())).fetch().rowsUpdated().block();
        var late = send(s.scorer(), queued.get(2));
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().get("code")).isEqualTo("MATCH_LOCKED");
        // The sender stops at the first refusal, so the fourth item is not sent; it keeps waiting with its id.
        assertThat(eventCount(s.matchId())).as("the two before the deadline are in, nothing else").isEqualTo(38);
        assertSeqIsContiguous(s.matchId(), 38);

        var admin = administrator();
        var unlock = post(admin, "/api/v1/admin/matches/" + s.matchId() + "/scoring/unlock", Map.of("reason", "the scorer's phone had no signal at the ground"));
        assertThat(unlock.status()).isEqualTo(200);
        assertThat(unlock.body().get("eventSeq")).isEqualTo(39);
        var again = send(s.scorer(), queued.get(2).at(39));
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("eventSeq")).isEqualTo(40);
        assertThat(send(s.scorer(), queued.get(3).at(40)).status()).isEqualTo(200);
        assertSeqIsContiguous(s.matchId(), 41);
        var entries = store.entries(UUID.fromString(s.matchId())).collectList().block();
        assertThat(entries.get(39).clientEventId()).isEqualTo(queued.get(2).clientEventId());
        assertThat(entries.get(40).clientEventId()).isEqualTo(queued.get(3).clientEventId());
    }

    @Test
    void aScorerRemovedMidFlushIsRefusedFromThatItemOnNothingIsWrittenAndTheSameItemGoesInOnceTheyAreAddedBack() {
        var s = setup(2);
        var items = sixtyItems(s, null);
        for (int i = 0; i < 3; i++) {
            assertThat(send(s.scorer(), items.get(i)).status()).isEqualTo(200);
        }
        assertThat(call(HttpMethod.DELETE, s.organiser(), "/api/v1/matches/" + s.matchId() + "/scorers/" + s.scorer().id(), null).status()).isIn(200, 204);
        var refused = send(s.scorer(), items.get(3));
        assertThat(refused.status()).isIn(403, 404);
        assertThat(eventCount(s.matchId())).isEqualTo(3);
        assertThat(post(s.organiser(), "/api/v1/matches/" + s.matchId() + "/scorers", Map.of("email", s.scorer().email())).status()).isEqualTo(201);
        var back = send(s.scorer(), items.get(3));
        assertThat(back.status()).isEqualTo(200);
        assertThat(back.body().get("eventSeq")).isEqualTo(4);
        assertSeqIsContiguous(s.matchId(), 4);
    }

    @Test
    void theEngineStateIsForTheSamePeopleAsTheScoringViewAndNobodyElse() {
        var s = setup(2);
        assertThat(get(null, s.scoring() + "/engine-state").status()).isEqualTo(401);
        assertThat(get(s.stranger(), s.scoring() + "/engine-state").status()).isEqualTo(404);
        assertThat(get(s.viewer(), s.scoring() + "/engine-state").status()).as("a team member who cannot score").isEqualTo(403);
        assertThat(post(s.organiser(), s.scoring() + "/engine-state", Map.of()).status()).as("read only").isIn(404, 405);
        assertThat(get(s.organiser(), "/api/v1/matches/" + UUID.randomUUID() + "/scoring/engine-state").status()).isEqualTo(404);
        assertThat(get(s.organiser(), "/api/v1/matches/not-an-id/scoring/engine-state").status()).as("a malformed id is a client error, never a 500").isBetween(400, 404);
        var first = engineState(s, s.organiser());
        assertThat(first).containsEntry("ready", true).containsEntry("seq", 0).containsEntry("engineVersion", 1);
        assertThat((List<?>) first.get("entries")).isEmpty();
        @SuppressWarnings("unchecked")
        var setup = (Map<String, Object>) first.get("setup");
        assertThat(setup.get("xiA")).isEqualTo(s.lions().playerIds());
        assertThat(first.toString()).as("ids only: no email, no name").doesNotContain("@example.com").doesNotContain("Lions player");
        assertThat(score(s, s.organiser(), 0, goldenMatch(s.lions(), s.tigers()).get(0)).status()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var own = (Map<String, Object>) ((List<?>) engineState(s, s.organiser()).get("entries")).get(0);
        @SuppressWarnings("unchecked")
        var theirs = (Map<String, Object>) ((List<?>) engineState(s, s.scorer()).get("entries")).get(0);
        assertThat(own).containsEntry("mine", true);
        assertThat(own.get("clientEventId")).isNotNull();
        assertThat(theirs).containsEntry("mine", false).containsEntry("clientEventId", null);
    }

    @Test
    void theTappedTimeIsOptionalKeptForTheTimelineClampedToWhenTheServerGotItAndABadOneIsAClientErrorNeverAServerError() {
        var s = setup(2);
        var commands = goldenMatch(s.lions(), s.tigers());
        assertThat(score(s, s.organiser(), 0, commands.get(0)).status()).isEqualTo(200);
        var past = java.time.Instant.now().minusSeconds(600);
        assertThat(send(s.organiser(), commandItem(s, 1, commands.get(1), past.toString())).status()).isEqualTo(200);
        assertThat(send(s.organiser(), commandItem(s, 2, commands.get(2), java.time.Instant.now().plus(java.time.Duration.ofDays(30)).toString())).status()).isEqualTo(200);
        var events = get(s.organiser(), s.scoring()).body().get("recentEvents");
        @SuppressWarnings("unchecked")
        var list = (List<Map<String, Object>>) events;
        assertThat(list.get(0).get("tappedAt")).isNull();
        assertThat(java.time.Instant.parse((String) list.get(1).get("tappedAt"))).isCloseTo(past, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
        assertThat(java.time.Instant.parse((String) list.get(2).get("tappedAt"))).as("a wrong phone clock cannot place a ball in the future").isBeforeOrEqualTo(java.time.Instant.parse((String) list.get(2).get("at")));
        assertThat(java.time.Instant.parse((String) list.get(1).get("at"))).as("the server's own time is what is recorded as the time").isAfter(past);

        var ancient = send(s.organiser(), commandItem(s, 3, commands.get(3), "-999999999-01-01T00:00:00Z"));
        assertThat(ancient.status()).as("a time the database could not store is dropped, not a 500").isEqualTo(200);
        assertThat(eventCount(s.matchId())).isEqualTo(4);
        var bad = commandItem(s, 4, commands.get(4), "yesterday afternoon");
        var r = send(s.organiser(), bad);
        assertThat(r.status()).isBetween(400, 499);
        assertThat(eventCount(s.matchId())).isEqualTo(4);
        assertThat(send(s.organiser(), commandItem(s, 4, commands.get(4), null)).status()).isEqualTo(200);
    }

    // ---- the public analytics (Phase 3a) -------------------------------------------------------------------------------------

    @Test
    void theAnalyticsArePublicOnceTheMatchHasStartedWithNamesAndNumbersOnlyAndAnETagThatMovesWithTheBalls() {
        var s = setup(2);
        var url = "/api/v1/matches/" + s.matchId() + "/analytics";
        assertThat(get(null, url).status()).as("not started: the same as a match that does not exist").isEqualTo(404);
        assertThat(get(null, "/api/v1/matches/" + UUID.randomUUID() + "/analytics").status()).isEqualTo(404);

        var commands = goldenMatch(s.lions(), s.tigers());
        for (int i = 0; i < 5; i++) {
            assertThat(score(s, s.scorer(), i, commands.get(i)).status()).isEqualTo(200);
        }
        var first = web.get().uri(url).exchange().expectStatus().isOk().expectHeader().exists("ETag").expectBody(Map.class).returnResult();
        var tag = first.getResponseHeaders().getETag();
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) first.getResponseBody();
        assertThat(body.get("seq")).isEqualTo(5);
        assertThat((List<?>) body.get("commentary")).hasSize(4);
        var text = body.toString();
        assertThat(text).as("names and numbers only").doesNotContain("@example.com").doesNotContain(s.lions().playerIds().get(0)).doesNotContain(s.matchId().substring(0, 8));
        assertThat(text).contains("Lions player 1").contains("Tigers player 1");

        web.get().uri(url).header("If-None-Match", tag).exchange().expectStatus().isNotModified();
        assertThat(score(s, s.scorer(), 5, commands.get(5)).status()).isEqualTo(200);
        web.get().uri(url).header("If-None-Match", tag).exchange().expectStatus().isOk();

        assertThat(get(null, url + "/x").status()).as("no other path is opened by this one").isIn(401, 404);
        assertThat(post(null, url, Map.of()).status()).isIn(401, 403, 405);
        assertThat(get(null, "/api/v1/matches/" + s.matchId() + "/analytic").status()).isEqualTo(401);
    }
}
