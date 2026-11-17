package com.dsports.cricket.application;

import com.dsports.cricket.application.usecase.Actor;
import com.dsports.cricket.application.usecase.ManagePlayersUseCase;
import com.dsports.cricket.application.usecase.ManageTeamsUseCase;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.Player;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManageTeamsUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Actor OWNER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final Actor CAPTAIN = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c2"));
    private static final Actor MEMBER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
    private static final Actor STRANGER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c4"));

    private final Fakes.Players players = new Fakes.Players();
    private final Fakes.Teams teams = new Fakes.Teams();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ManagePlayersUseCase managePlayers = new ManagePlayersUseCase(players, clock);
    private final ManageTeamsUseCase manageTeams = new ManageTeamsUseCase(teams, players, clock);

    {
        teams.players = players;
    }

    private static void assertCode(Callable<?> action, CricketErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    private UUID guest(Actor creator, String name, String phone) {
        return managePlayers.createGuest(creator, new Player.Details(name, phone, null, null, null, null)).block().id();
    }

    @Test
    void aTeamIsCreatedForTheCallerAndNamesAreUniquePerCreatorIgnoringCase() {
        var team = manageTeams.create(OWNER, "Green Lions", "Chennai").block();

        assertThat(team.canEdit()).isTrue();
        assertThat(team.members()).isEmpty();
        assertCode(() -> manageTeams.create(OWNER, "green lions", null).block(), CricketErrorCode.DUPLICATE_TEAM_NAME);
        assertThat(manageTeams.create(STRANGER, "Green Lions", null).block().id()).isNotEqualTo(team.id());
    }

    @Test
    void membersCanBeAddedFromTheCallersOwnPlayersOnly() {
        var team = manageTeams.create(OWNER, "Lions", null).block();
        var mine = guest(OWNER, "Arjun", "9876543210");
        var someoneElses = guest(STRANGER, "Ravi", null);

        var updated = manageTeams.addMember(OWNER, team.id(), mine).block();

        assertThat(updated.members()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Arjun");
            assertThat(m.captain()).isFalse();
            assertThat(m.phone()).isEqualTo("+919876543210");
        });
        assertCode(() -> manageTeams.addMember(OWNER, team.id(), someoneElses).block(), CricketErrorCode.PLAYER_NOT_FOUND);
        assertCode(() -> manageTeams.addMember(OWNER, team.id(), mine).block(), CricketErrorCode.ALREADY_A_MEMBER);
        assertCode(() -> manageTeams.addMember(OWNER, team.id(), UUID.randomUUID()).block(), CricketErrorCode.PLAYER_NOT_FOUND);
    }

    @Test
    void aTeamIsInvisibleToStrangersWhoGetNotFoundNotForbidden() {
        var team = manageTeams.create(OWNER, "Lions", null).block();

        assertCode(() -> manageTeams.get(STRANGER, team.id()).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertCode(() -> manageTeams.update(STRANGER, team.id(), "Mine now", null, team.version()).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertCode(() -> manageTeams.addMember(STRANGER, team.id(), guest(STRANGER, "X", null)).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertCode(() -> manageTeams.archive(STRANGER, team.id()).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertThat(manageTeams.listMine(STRANGER).collectList().block()).isEmpty();
        assertThat(manageTeams.get(OWNER, team.id()).block().name()).isEqualTo("Lions");
    }

    @Test
    void theCaptainsOwnAccountCanEditTheTeamEvenThoughTheyDidNotCreateIt() {
        var team = manageTeams.create(OWNER, "Lions", null).block();
        var captainGuest = guest(OWNER, "Captain Cool", null);
        players.store.get(captainGuest).linkTo(CAPTAIN.id(), NOW);
        manageTeams.addMember(OWNER, team.id(), captainGuest).block();
        manageTeams.setCaptain(OWNER, team.id(), captainGuest).block();

        var seen = manageTeams.get(CAPTAIN, team.id()).block();
        var renamed = manageTeams.update(CAPTAIN, team.id(), "Lions 2", "Pune", seen.version()).block();

        assertThat(seen.canEdit()).isTrue();
        assertThat(renamed.name()).isEqualTo("Lions 2");
        assertThat(renamed.members()).singleElement().satisfies(m -> assertThat(m.phone()).isNull());
    }

    @Test
    void aMemberLinkedToAnAccountCanSeeButNotEditTheTeam() {
        var team = manageTeams.create(OWNER, "Lions", null).block();
        var memberGuest = guest(OWNER, "Mid Fielder", null);
        var memberAccount = Actor.user(UUID.randomUUID());
        players.store.get(memberGuest).linkTo(memberAccount.id(), NOW);
        manageTeams.addMember(OWNER, team.id(), memberGuest).block();

        var seen = manageTeams.get(memberAccount, team.id()).block();

        assertThat(seen.canEdit()).isFalse();
        assertThat(seen.members()).singleElement().satisfies(m -> assertThat(m.phone()).isNull());
        assertCode(() -> manageTeams.update(memberAccount, team.id(), "Nope", null, seen.version()).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageTeams.archive(memberAccount, team.id()).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageTeams.setCaptain(memberAccount, team.id(), memberGuest).block(), CricketErrorCode.NOT_ALLOWED);
        assertThat(manageTeams.listMine(memberAccount).collectList().block()).hasSize(1);
    }

    @Test
    void thereIsOneCaptainAndTheCaptainCannotBeRemoved() {
        var team = manageTeams.create(OWNER, "Lions", null).block();
        var a = guest(OWNER, "Anil", null);
        var b = guest(OWNER, "Bala", null);
        manageTeams.addMember(OWNER, team.id(), a).block();
        manageTeams.addMember(OWNER, team.id(), b).block();

        manageTeams.setCaptain(OWNER, team.id(), a).block();
        var switched = manageTeams.setCaptain(OWNER, team.id(), b).block();

        assertThat(switched.members().stream().filter(m -> m.captain())).singleElement().satisfies(m -> assertThat(m.name()).isEqualTo("Bala"));
        assertThat(switched.members().get(0).captain()).as("the captain is listed first").isTrue();
        assertCode(() -> manageTeams.removeMember(OWNER, team.id(), b).block(), CricketErrorCode.CANNOT_REMOVE_CAPTAIN);
        assertThat(manageTeams.removeMember(OWNER, team.id(), a).block().members()).hasSize(1);
        assertCode(() -> manageTeams.setCaptain(OWNER, team.id(), a).block(), CricketErrorCode.NOT_A_MEMBER);
    }

    @Test
    void aRenameNeedsTheVersionTheCallerSawAndAnArchivedTeamIsFrozen() {
        var team = manageTeams.create(OWNER, "Lions", null).block();

        var renamed = manageTeams.update(OWNER, team.id(), "Lions XI", "Pune", team.version()).block();

        assertThat(renamed.name()).isEqualTo("Lions XI");
        assertCode(() -> manageTeams.update(OWNER, team.id(), "Stale", null, team.version()).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        var archived = manageTeams.archive(OWNER, team.id()).block();
        assertThat(archived.status()).isEqualTo("ARCHIVED");
        assertCode(() -> manageTeams.update(OWNER, team.id(), "Frozen", null, archived.version()).block(), CricketErrorCode.TEAM_ARCHIVED);
        assertCode(() -> manageTeams.addMember(OWNER, team.id(), guest(OWNER, "Late", null)).block(), CricketErrorCode.TEAM_ARCHIVED);
    }

    @Test
    void theListShowsMyTeamsByName() {
        manageTeams.create(OWNER, "Zebras", null).block();
        manageTeams.create(OWNER, "Aces", null).block();
        manageTeams.create(STRANGER, "Others", null).block();

        assertThat(manageTeams.listMine(OWNER).map(t -> t.name()).collectList().block()).containsExactly("Aces", "Zebras");
    }
}
