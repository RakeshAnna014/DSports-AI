package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.model.TossDecision;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V54 and the match adapter on a real PostgreSQL: round trips with playing XIs and scorers, optimistic locking, the booking and toss constraints, and who finds which match. */
@Tag("integration")
class MatchPersistenceIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static PlayerR2dbcRepositoryAdapter players;
    private static TeamR2dbcRepositoryAdapter teams;
    private static MatchR2dbcRepositoryAdapter matches;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        var connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        var db = DatabaseClient.create(connectionFactory);
        players = new PlayerR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcPlayerRepository.class));
        teams = new TeamR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcTeamRepository.class), db);
        matches = new MatchR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcMatchRepository.class), db);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /** A saved team of {@code size} guest players created by {@code creator}. */
    private record Side(Team team, List<UUID> playerIds) {
    }

    private static Side side(UUID creator, String name, int size) {
        var team = Team.create(creator, name + " " + UUID.randomUUID(), "Chennai", NOW);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < size; i++) {
            var p = players.save(Player.guest(creator, new Player.Details(name + i, null, null, null, null, null), NOW)).block();
            team.addMember(p.getId(), NOW);
            ids.add(p.getId());
        }
        teams.save(team).block();
        return new Side(team, ids);
    }

    private static Match.Settings settings() {
        return new Match.Settings(20, BallType.TENNIS, new MatchRules(8, 9, 3, 2, 2, false, true), NOW.plusSeconds(3600), "Chennai", "Ravi, Sam");
    }

    private static Match newMatch(UUID creator, Side a, Side b, Match.Venue venue) {
        return Match.create(creator, a.team().getId(), b.team().getId(), settings(), venue, NOW);
    }

    private static void assertCode(Runnable action, CricketErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void aMatchSurvivesARoundTripWithSettingsRulesTossXisAndScorers() {
        var creator = UUID.randomUUID();
        var scorer = UUID.randomUUID();
        var a = side(creator, "A", 9);
        var b = side(creator, "B", 5);
        var booking = UUID.randomUUID();
        var match = newMatch(creator, a, b, new Match.Venue(booking, "Green Park", "Pune"));
        match.recordToss(b.team().getId(), TossDecision.BAT, NOW);
        match.setPlayingXi(a.team().getId(), a.playerIds(), Set.copyOf(a.playerIds()), NOW);
        match.setPlayingXi(b.team().getId(), b.playerIds().subList(0, 4), Set.copyOf(b.playerIds()), NOW);
        match.addScorer(scorer, NOW);
        matches.save(match).block();

        var loaded = matches.findById(match.getId()).block();

        assertThat(loaded.getStatus()).isEqualTo(MatchStatus.TOSS_DONE);
        assertThat(loaded.getSettings().overs()).isEqualTo(20);
        assertThat(loaded.getSettings().ballType()).isEqualTo(BallType.TENNIS);
        assertThat(loaded.getSettings().rules()).isEqualTo(new MatchRules(8, 9, 3, 2, 2, false, true));
        assertThat(loaded.getSettings().scheduledAt()).isEqualTo(NOW.plusSeconds(3600));
        assertThat(loaded.getSettings().umpires()).isEqualTo("Ravi, Sam");
        assertThat(loaded.getVenue().bookingId()).isEqualTo(booking);
        assertThat(loaded.getVenue().name()).isEqualTo("Green Park");
        assertThat(loaded.getTossWinnerTeamId()).isEqualTo(b.team().getId());
        assertThat(loaded.battingFirstTeamId()).isEqualTo(b.team().getId());
        assertThat(loaded.playingXi(a.team().getId())).containsExactlyElementsOf(a.playerIds());
        assertThat(loaded.playingXi(b.team().getId())).containsExactlyElementsOf(b.playerIds().subList(0, 4));
        assertThat(loaded.getScorers()).containsExactly(scorer);
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void changingTheXiOrTheScorersReplacesTheRowsAndBumpsTheVersion() {
        var creator = UUID.randomUUID();
        var a = side(creator, "A", 6);
        var b = side(creator, "B", 6);
        var match = newMatch(creator, a, b, null);
        match.setPlayingXi(a.team().getId(), a.playerIds().subList(0, 3), Set.copyOf(a.playerIds()), NOW);
        match.addScorer(UUID.randomUUID(), NOW);
        matches.save(match).block();

        var loaded = matches.findById(match.getId()).block();
        var reversed = new ArrayList<>(a.playerIds().subList(3, 6));
        loaded.setPlayingXi(a.team().getId(), reversed, Set.copyOf(a.playerIds()), NOW);
        loaded.removeScorer(loaded.getScorers().iterator().next(), NOW);
        matches.save(loaded).block();

        var reloaded = matches.findById(match.getId()).block();
        assertThat(reloaded.playingXi(a.team().getId())).containsExactlyElementsOf(reversed);
        assertThat(reloaded.getScorers()).isEmpty();
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void aStaleWriteIsRejectedBeforeAnyChildRowIsTouched() {
        var creator = UUID.randomUUID();
        var a = side(creator, "A", 4);
        var b = side(creator, "B", 4);
        var match = newMatch(creator, a, b, null);
        matches.save(match).block();
        var first = matches.findById(match.getId()).block();
        var second = matches.findById(match.getId()).block();
        first.setPlayingXi(a.team().getId(), a.playerIds(), Set.copyOf(a.playerIds()), NOW);
        matches.save(first).block();
        second.setPlayingXi(b.team().getId(), b.playerIds(), Set.copyOf(b.playerIds()), NOW);

        assertCode(() -> matches.save(second).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertThat(matches.findById(match.getId()).block().playingXi(b.team().getId())).isEmpty();
        assertThat(matches.findById(match.getId()).block().playingXi(a.team().getId())).hasSize(4);
    }

    @Test
    void oneBookingCanHostOnlyOneMatch() {
        var creator = UUID.randomUUID();
        var a = side(creator, "A", 2);
        var b = side(creator, "B", 2);
        var venue = new Match.Venue(UUID.randomUUID(), "Green Park", "Pune");
        matches.save(newMatch(creator, a, b, venue)).block();

        assertCode(() -> matches.save(newMatch(creator, a, b, venue)).block(), CricketErrorCode.BOOKING_ALREADY_USED);
        matches.save(newMatch(creator, a, b, null)).block();
        matches.save(newMatch(creator, a, b, null)).block();
    }

    @Test
    void findMineCoversCreatedScoredAndTeamMatchesNewestFirst() {
        var creator = UUID.randomUUID();
        var scorer = UUID.randomUUID();
        var member = UUID.randomUUID();
        var a = side(creator, "A", 3);
        var b = side(creator, "B", 3);
        var linked = players.findById(a.playerIds().get(0)).block();
        linked.linkTo(member, NOW);
        players.save(linked).block();
        var older = newMatch(creator, a, b, null);
        matches.save(older).block();
        var newer = Match.create(creator, a.team().getId(), b.team().getId(), settings(), null, NOW.plusSeconds(60));
        newer.addScorer(scorer, NOW);
        matches.save(newer).block();

        assertThat(matches.findMine(creator).collectList().block()).extracting(Match::getId).containsExactly(newer.getId(), older.getId());
        assertThat(matches.findMine(scorer).collectList().block()).extracting(Match::getId).containsExactly(newer.getId());
        assertThat(matches.findMine(member).collectList().block()).extracting(Match::getId).containsExactlyInAnyOrder(newer.getId(), older.getId());
        assertThat(matches.findMine(UUID.randomUUID()).collectList().block()).isEmpty();
    }

    /** A match with its toss done, saved, then moved by SQL to {@code status} in {@code city} as if it had been scored, last touched at {@code touched}. */
    private static Match matchAt(Side a, Side b, String status, String city, Instant touched) throws SQLException {
        var match = Match.create(UUID.randomUUID(), a.team().getId(), b.team().getId(), new Match.Settings(20, BallType.TENNIS, MatchRules.standard(), null, city, null), null, NOW);
        match.recordToss(a.team().getId(), TossDecision.BAT, NOW);
        matches.save(match).block();
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            c.createStatement().executeUpdate("UPDATE matches SET status = '" + status + "', updated_at = '" + touched + "' WHERE id = '" + match.getId() + "'");
        }
        return match;
    }

    @Test
    void findLiveReturnsOnlyMatchesBeingPlayedByCityWithoutRegardToCaseNewestFirstAndPaged() throws SQLException {
        var creator = UUID.randomUUID();
        var a = side(creator, "A", 2);
        var b = side(creator, "B", 2);
        var city = "Live" + UUID.randomUUID().toString().substring(0, 8);
        var oldest = matchAt(a, b, "IN_PROGRESS", city, NOW.plusSeconds(10));
        var middle = matchAt(a, b, "INNINGS_BREAK", city.toUpperCase(), NOW.plusSeconds(20));
        var newest = matchAt(a, b, "IN_PROGRESS", city.toLowerCase(), NOW.plusSeconds(30));
        matchAt(a, b, "COMPLETED", city, NOW.plusSeconds(40));
        matchAt(a, b, "TOSS_DONE", city, NOW.plusSeconds(50));
        matchAt(a, b, "IN_PROGRESS", city + "x", NOW.plusSeconds(60));

        assertThat(matches.findLive(city, 10, 0).collectList().block()).extracting(Match::getId).containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(matches.findLive(city.toUpperCase(), 10, 0).collectList().block()).hasSize(3);
        assertThat(matches.findLive(city, 2, 0).collectList().block()).extracting(Match::getId).containsExactly(newest.getId(), middle.getId());
        assertThat(matches.findLive(city, 2, 2).collectList().block()).extracting(Match::getId).containsExactly(oldest.getId());
        assertThat(matches.findLive(city, 2, 3).collectList().block()).isEmpty();
        assertThat(matches.findLive(null, 1000, 0).collectList().block()).extracting(Match::getId).contains(newest.getId(), middle.getId(), oldest.getId())
            .allSatisfy(id -> assertThat(matches.findById(id).block().getStatus().isLive()).isTrue());
    }

    @Test
    void theDatabaseRefusesInconsistentMatchesAndXis() throws SQLException {
        var creator = UUID.randomUUID();
        var a = side(creator, "A", 3);
        var b = side(creator, "B", 3);
        var match = newMatch(creator, a, b, null);
        match.setPlayingXi(a.team().getId(), a.playerIds(), Set.copyOf(a.playerIds()), NOW);
        matches.save(match).block();

        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET team_b_id = team_a_id WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_teams");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET status = 'TOSS_DONE' WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_toss_done");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET toss_winner_team_id = team_a_id WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_scheduled_no_toss");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET status = 'TOSS_DONE', toss_winner_team_id = team_a_id WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_toss");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET overs = 101 WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_overs");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET max_overs_per_bowler = 21 WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_bowler_limit");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("UPDATE matches SET status = 'LIVE' WHERE id = '" + match.getId() + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("ck_matches_status");
            // The same player cannot be listed in two sides of one match, nor two players at one batting position.
            assertThatThrownBy(() -> c.createStatement().executeUpdate("INSERT INTO match_players (match_id, team_id, player_id, batting_order) VALUES ('" + match.getId() + "', '"
                + b.team().getId() + "', '" + a.playerIds().get(0) + "', 1)")).isInstanceOf(SQLException.class).hasMessageContaining("match_players_pkey");
            assertThatThrownBy(() -> c.createStatement().executeUpdate("INSERT INTO match_players (match_id, team_id, player_id, batting_order) VALUES ('" + match.getId() + "', '"
                + a.team().getId() + "', '" + b.playerIds().get(0) + "', 1)")).isInstanceOf(SQLException.class).hasMessageContaining("uq_match_players_order");
        }
    }
}
