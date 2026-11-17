package com.dsports.cricket.infrastructure.persistence;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayerClaim;
import com.dsports.cricket.domain.model.PlayingRole;
import com.dsports.cricket.domain.model.Team;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V53 and the adapters on a real PostgreSQL: round trips, the unique and partial-unique constraints, optimistic locking and member replacement. */
@Tag("integration")
class CricketPersistenceIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static PlayerR2dbcRepositoryAdapter players;
    private static TeamR2dbcRepositoryAdapter teams;
    private static ClaimR2dbcRepositoryAdapter claims;

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
        claims = new ClaimR2dbcRepositoryAdapter(db);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Player guest(UUID creator, String name, String phone) {
        return players.save(Player.guest(creator, new Player.Details(name, phone, PlayingRole.BOWLER, BattingHand.LEFT, "left-arm spin", "https://x.example/p.jpg"), NOW)).block();
    }

    private static void assertCode(Runnable action, CricketErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void aPlayerSurvivesARoundTripWithEveryField() {
        var creator = UUID.randomUUID();
        var saved = guest(creator, "Arjun Kumar", "9876543210");

        var loaded = players.findById(saved.getId()).block();

        assertThat(loaded.getName()).isEqualTo("Arjun Kumar");
        assertThat(loaded.getPhone()).isEqualTo("+919876543210");
        assertThat(loaded.getDetails().role()).isEqualTo(PlayingRole.BOWLER);
        assertThat(loaded.getDetails().battingHand()).isEqualTo(BattingHand.LEFT);
        assertThat(loaded.getDetails().bowlingStyle()).isEqualTo("left-arm spin");
        assertThat(loaded.getCreatedBy()).isEqualTo(creator);
        assertThat(loaded.isLinked()).isFalse();
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void thePhoneIsUniquePerCreatorAndAnotherCreatorMayUseTheSameNumber() {
        var creator = UUID.randomUUID();
        guest(creator, "Arjun", "9876543211");

        assertCode(() -> guest(creator, "Arjun again", "+91 98765 43211"), CricketErrorCode.DUPLICATE_PLAYER_PHONE);
        assertThat(guest(UUID.randomUUID(), "Arjun K", "9876543211").getId()).isNotNull();
        guest(creator, "No phone 1", null);
        guest(creator, "No phone 2", null);
    }

    @Test
    void aCustomerCanBeLinkedToOnlyOnePlayer() {
        var customer = UUID.randomUUID();
        players.save(Player.forCustomer(customer, new Player.Details("Me", null, null, null, null, null), NOW)).block();

        assertCode(() -> players.save(Player.forCustomer(customer, new Player.Details("Me twice", null, null, null, null, null), NOW)).block(), CricketErrorCode.CLAIMANT_ALREADY_LINKED);
        assertThat(players.findByCustomer(customer).block().getName()).isEqualTo("Me");
    }

    @Test
    void aStalePlayerWriteIsRejected() {
        var saved = guest(UUID.randomUUID(), "Arjun", null);
        var first = players.findById(saved.getId()).block();
        var second = players.findById(saved.getId()).block();
        first.update(new Player.Details("First", null, null, null, null, null), NOW);
        players.save(first).block();
        second.update(new Player.Details("Second", null, null, null, null, null), NOW);

        assertCode(() -> players.save(second).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertThat(players.findById(saved.getId()).block().getName()).isEqualTo("First");
    }

    @Test
    void usableByCoversCreatedAndLinkedPlayersAndTheUnlinkedPhoneSearchSkipsLinkedOnes() {
        var creator = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var mine = guest(creator, "Mine", "9876543212");
        var linkedElsewhere = guest(UUID.randomUUID(), "Linked elsewhere", "9876543213");
        linkedElsewhere.linkTo(customer, NOW);
        players.save(linkedElsewhere).block();

        assertThat(players.findUsableBy(creator).collectList().block()).extracting(Player::getId).containsExactly(mine.getId());
        assertThat(players.findUsableBy(customer).collectList().block()).extracting(Player::getId).containsExactly(linkedElsewhere.getId());
        assertThat(players.findUnlinkedByPhone("+919876543212", 5).collectList().block()).hasSize(1);
        assertThat(players.findUnlinkedByPhone("+919876543213", 5).collectList().block()).isEmpty();
    }

    @Test
    void aTeamRoundTripsWithItsMembersAndTheCaptain() {
        var creator = UUID.randomUUID();
        var a = guest(creator, "Anil", null);
        var b = guest(creator, "Bala", null);
        var team = Team.create(creator, "Lions " + UUID.randomUUID(), "Chennai", NOW);
        team.addMember(a.getId(), NOW);
        team.addMember(b.getId(), NOW);
        team.setCaptain(b.getId(), NOW);
        teams.save(team).block();

        var loaded = teams.findById(team.getId()).block();

        assertThat(loaded.getMembers()).hasSize(2);
        assertThat(loaded.captainId()).contains(b.getId());
        assertThat(loaded.getCity()).isEqualTo("Chennai");
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void changingMembersReplacesTheRowsAndBumpsTheVersion() {
        var creator = UUID.randomUUID();
        var a = guest(creator, "Anil", null);
        var b = guest(creator, "Bala", null);
        var team = Team.create(creator, "Replace " + UUID.randomUUID(), null, NOW);
        team.addMember(a.getId(), NOW);
        teams.save(team).block();

        var loaded = teams.findById(team.getId()).block();
        loaded.addMember(b.getId(), NOW);
        loaded.removeMember(a.getId(), NOW);
        teams.save(loaded).block();

        var reloaded = teams.findById(team.getId()).block();
        assertThat(reloaded.getMembers()).extracting(Team.Member::playerId).containsExactly(b.getId());
        assertThat(reloaded.getVersion()).isEqualTo(2);
    }

    @Test
    void aStaleTeamWriteIsRejectedBeforeAnyMemberRowIsTouched() {
        var creator = UUID.randomUUID();
        var a = guest(creator, "Anil", null);
        var b = guest(creator, "Bala", null);
        var team = Team.create(creator, "Stale " + UUID.randomUUID(), null, NOW);
        teams.save(team).block();
        var first = teams.findById(team.getId()).block();
        var second = teams.findById(team.getId()).block();
        first.addMember(a.getId(), NOW);
        teams.save(first).block();
        second.addMember(b.getId(), NOW);

        assertCode(() -> teams.save(second).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertThat(teams.findById(team.getId()).block().getMembers()).extracting(Team.Member::playerId).containsExactly(a.getId());
    }

    @Test
    void teamNamesAreUniquePerCreatorInAnyCase() {
        var creator = UUID.randomUUID();
        teams.save(Team.create(creator, "Dup Name", null, NOW)).block();

        assertCode(() -> teams.save(Team.create(creator, "dup name", null, NOW)).block(), CricketErrorCode.DUPLICATE_TEAM_NAME);
        assertThat(teams.save(Team.create(UUID.randomUUID(), "Dup Name", null, NOW)).block()).isNotNull();
    }

    @Test
    void findMineIncludesTeamsICreatedAndTeamsWithMyLinkedPlayer() {
        var creator = UUID.randomUUID();
        var customer = UUID.randomUUID();
        var member = guest(creator, "Linked", null);
        member.linkTo(customer, NOW);
        players.save(member).block();
        var team = Team.create(creator, "Mine " + UUID.randomUUID(), null, NOW);
        team.addMember(member.getId(), NOW);
        teams.save(team).block();

        assertThat(teams.findMine(creator).collectList().block()).extracting(Team::getId).contains(team.getId());
        assertThat(teams.findMine(customer).collectList().block()).extracting(Team::getId).containsExactly(team.getId());
        assertThat(teams.findMine(UUID.randomUUID()).collectList().block()).isEmpty();
    }

    @Test
    void theDatabaseItselfAllowsOnlyOneCaptainPerTeam() throws SQLException {
        var creator = UUID.randomUUID();
        var a = guest(creator, "Anil", null);
        var b = guest(creator, "Bala", null);
        var team = Team.create(creator, "OneCaptain " + UUID.randomUUID(), null, NOW);
        team.addMember(a.getId(), NOW);
        team.addMember(b.getId(), NOW);
        team.setCaptain(a.getId(), NOW);
        teams.save(team).block();

        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var s = c.prepareStatement("UPDATE team_members SET captain = TRUE WHERE team_id = ? AND player_id = ?")) {
            s.setObject(1, team.getId());
            s.setObject(2, b.getId());
            assertThatThrownBy(s::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("uq_team_one_captain");
        }
    }

    @Test
    void claimsAreSavedDecidedAndQueriedByCreatorClaimantAndPlayer() {
        var creator = UUID.randomUUID();
        var claimant = UUID.randomUUID();
        var player = guest(creator, "Arjun", "9876543214");
        var claim = claims.save(PlayerClaim.request(player.getId(), claimant, NOW)).block();

        assertThat(claims.existsPending(player.getId(), claimant).block()).isTrue();
        assertThat(claims.findPendingForCreator(creator).collectList().block()).extracting(PlayerClaim::getId).containsExactly(claim.getId());
        assertThat(claims.findPendingForCreator(UUID.randomUUID()).collectList().block()).isEmpty();
        assertThat(claims.findPendingByPlayer(player.getId()).collectList().block()).hasSize(1);
        assertThat(claims.findPending(100).collectList().block()).extracting(PlayerClaim::getId).contains(claim.getId());

        var decider = UUID.randomUUID();
        var loaded = claims.findById(claim.getId()).block();
        loaded.approve(decider, NOW.plusSeconds(30));
        claims.save(loaded).block();

        var decided = claims.findById(claim.getId()).block();
        assertThat(decided.getStatus().name()).isEqualTo("APPROVED");
        assertThat(decided.getDecidedBy()).isEqualTo(decider);
        assertThat(decided.getDecidedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(claims.existsPending(player.getId(), claimant).block()).isFalse();
        assertThat(claims.findByClaimant(claimant).collectList().block()).hasSize(1);
    }

    @Test
    void onlyOneOpenClaimPerPlayerAndClaimantButARejectedOneAllowsANewRequest() {
        var player = guest(UUID.randomUUID(), "Arjun", null);
        var claimant = UUID.randomUUID();
        var first = claims.save(PlayerClaim.request(player.getId(), claimant, NOW)).block();

        assertThatThrownBy(() -> claims.save(PlayerClaim.request(player.getId(), claimant, NOW)).block()).hasMessageContaining("uq_player_claims_open");
        first.reject(UUID.randomUUID(), NOW);
        claims.save(first).block();
        assertThat(claims.save(PlayerClaim.request(player.getId(), claimant, NOW.plusSeconds(60))).block()).isNotNull();
    }
}
