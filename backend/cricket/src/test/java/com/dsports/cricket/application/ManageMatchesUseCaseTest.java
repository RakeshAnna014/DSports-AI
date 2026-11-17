package com.dsports.cricket.application;

import com.dsports.cricket.application.port.BookingLookupPort;
import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.usecase.Actor;
import com.dsports.cricket.application.usecase.ManageMatchesUseCase;
import com.dsports.cricket.application.usecase.ManagePlayersUseCase;
import com.dsports.cricket.application.usecase.ManageTeamsUseCase;
import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.TossDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManageMatchesUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final Actor ORGANISER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final Actor SCORER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c2"));
    private static final Actor STRANGER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c4"));

    private final Fakes.Players players = new Fakes.Players();
    private final Fakes.Teams teams = new Fakes.Teams();
    private final Fakes.Matches matches = new Fakes.Matches();
    private final Fakes.Bookings bookings = new Fakes.Bookings();
    private final Fakes.Directory directory = new Fakes.Directory();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ManagePlayersUseCase managePlayers = new ManagePlayersUseCase(players, clock);
    private final ManageTeamsUseCase manageTeams = new ManageTeamsUseCase(teams, players, clock);
    private final ManageMatchesUseCase manageMatches = new ManageMatchesUseCase(matches, teams, players, bookings, directory, clock);

    private UUID teamA;
    private UUID teamB;
    private final List<UUID> membersA = new ArrayList<>();
    private final List<UUID> membersB = new ArrayList<>();

    {
        teams.players = players;
        directory.people.put(SCORER.id(), new CustomerDirectoryPort.Person("Sam Scorer", "sam@example.com"));
        directory.people.put(ORGANISER.id(), new CustomerDirectoryPort.Person("Olive Organiser", "olive@example.com"));
        teamA = team("Lions", membersA, 11);
        teamB = team("Tigers", membersB, 11);
    }

    private UUID team(String name, List<UUID> into, int size) {
        var id = manageTeams.create(ORGANISER, name, "Chennai").block().id();
        for (int i = 0; i < size; i++) {
            var p = managePlayers.createGuest(ORGANISER, new Player.Details(name + " player " + i, null, null, null, null, null)).block().id();
            manageTeams.addMember(ORGANISER, id, p).block();
            into.add(p);
        }
        return id;
    }

    private static Match.Settings settings() {
        return new Match.Settings(20, BallType.LEATHER, MatchRules.standard(), null, null, null);
    }

    private ManageMatchesUseCase.CreateCommand create(UUID booking) {
        return new ManageMatchesUseCase.CreateCommand(teamA, teamB, settings(), booking);
    }

    private static void assertCode(Callable<?> action, CricketErrorCode code) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(CricketDomainException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    // ---- creating --------------------------------------------------------------------------------------------

    @Test
    void anOrganiserCreatesAMatchBetweenTwoOfTheirTeams() {
        var created = manageMatches.create(ORGANISER, create(null)).block();

        assertThat(created.status()).isEqualTo("SCHEDULED");
        assertThat(created.teamA().teamName()).isEqualTo("Lions");
        assertThat(created.teamB().teamName()).isEqualTo("Tigers");
        assertThat(created.canManage()).isTrue();
        assertThat(created.canScore()).isTrue();
        assertThat(created.readyToStart()).isFalse();
        assertThat(created.rules().playersPerSide()).isEqualTo(11);
    }

    @Test
    void aTeamThatIsNotTheCallersCannotBeUsedAndANonExistentOneLooksTheSame() {
        assertCode(() -> manageMatches.create(STRANGER, create(null)).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertCode(() -> manageMatches.create(ORGANISER, new ManageMatchesUseCase.CreateCommand(teamA, UUID.randomUUID(), settings(), null)).block(), CricketErrorCode.TEAM_NOT_FOUND);
        assertCode(() -> manageMatches.create(ORGANISER, new ManageMatchesUseCase.CreateCommand(teamA, teamA, settings(), null)).block(), CricketErrorCode.INVALID_TEAMS);
        assertThat(matches.store).isEmpty();
    }

    @Test
    void anArchivedTeamCannotPlayANewMatch() {
        manageTeams.archive(ORGANISER, teamB).block();

        assertCode(() -> manageMatches.create(ORGANISER, create(null)).block(), CricketErrorCode.TEAM_ARCHIVED);
    }

    @Test
    void aTeamIsUsableThroughALinkedPlayerToo() {
        var memberAccount = Actor.user(UUID.randomUUID());
        players.store.get(membersA.get(0)).linkTo(memberAccount.id(), NOW);
        var otherTeam = manageTeams.create(memberAccount, "Own XI", null).block().id();

        var created = manageMatches.create(memberAccount, new ManageMatchesUseCase.CreateCommand(teamA, otherTeam, settings(), null)).block();

        assertThat(created.teamA().teamName()).isEqualTo("Lions");
        assertThat(created.canManage()).isTrue();
    }

    // ---- venue bookings --------------------------------------------------------------------------------------

    private UUID booking(Actor owner, String status, String sport) {
        var id = UUID.randomUUID();
        bookings.store.put(id, new BookingLookupPort.BookingInfo(id, owner.id(), status, sport, "Green Park Hub", "Pune", NOW.plusSeconds(172800)));
        return id;
    }

    @Test
    void aConfirmedCricketBookingOfTheCallersOwnGivesTheMatchItsVenueCityAndTime() {
        var id = booking(ORGANISER, "CONFIRMED", "cricket");

        var created = manageMatches.create(ORGANISER, create(id)).block();

        assertThat(created.venue().name()).isEqualTo("Green Park Hub");
        assertThat(created.venue().bookingId()).isEqualTo(id);
        assertThat(created.city()).isEqualTo("Pune");
        assertThat(created.scheduledAt()).isEqualTo(NOW.plusSeconds(172800));
    }

    @Test
    void aBookingThatIsNotConfirmedCricketOrNotTheCallersCannotBeUsed() {
        assertCode(() -> manageMatches.create(ORGANISER, create(booking(ORGANISER, "HELD", "cricket"))).block(), CricketErrorCode.BOOKING_NOT_USABLE);
        assertCode(() -> manageMatches.create(ORGANISER, create(booking(ORGANISER, "CANCELLED", "cricket"))).block(), CricketErrorCode.BOOKING_NOT_USABLE);
        assertCode(() -> manageMatches.create(ORGANISER, create(booking(ORGANISER, "CONFIRMED", "football"))).block(), CricketErrorCode.BOOKING_NOT_USABLE);
        assertCode(() -> manageMatches.create(ORGANISER, create(booking(STRANGER, "CONFIRMED", "cricket"))).block(), CricketErrorCode.BOOKING_NOT_USABLE);
        assertCode(() -> manageMatches.create(ORGANISER, create(UUID.randomUUID())).block(), CricketErrorCode.BOOKING_NOT_USABLE);
        assertThat(matches.store).isEmpty();
    }

    @Test
    void oneBookingHostsOneMatchOnly() {
        var id = booking(ORGANISER, "CONFIRMED", "cricket");
        manageMatches.create(ORGANISER, create(id)).block();

        assertCode(() -> manageMatches.create(ORGANISER, create(id)).block(), CricketErrorCode.BOOKING_ALREADY_USED);
    }

    // ---- visibility and permissions ---------------------------------------------------------------------------

    @Test
    void aMatchIsVisibleToItsCreatorScorersAndPeopleWithATeamInItAndToNobodyElse() {
        var m = manageMatches.create(ORGANISER, create(null)).block();
        manageMatches.addScorer(ORGANISER, m.id(), "sam@example.com").block();
        var member = Actor.user(UUID.randomUUID());
        players.store.get(membersB.get(3)).linkTo(member.id(), NOW);

        assertThat(manageMatches.get(ORGANISER, m.id()).block().id()).isEqualTo(m.id());
        assertThat(manageMatches.get(SCORER, m.id()).block().canScore()).isTrue();
        var seenByMember = manageMatches.get(member, m.id()).block();
        assertThat(seenByMember.canScore()).isFalse();
        assertThat(seenByMember.canManage()).isFalse();
        assertThat(seenByMember.scorers()).as("only scorers see the scorer list").isEmpty();
        assertThat(seenByMember.teamA().squad()).as("and only scorers see the squads").isEmpty();
        assertCode(() -> manageMatches.get(STRANGER, m.id()).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertCode(() -> manageMatches.get(ORGANISER, UUID.randomUUID()).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertCode(() -> manageMatches.recordToss(STRANGER, m.id(), teamA, TossDecision.BAT).block(), CricketErrorCode.MATCH_NOT_FOUND);
        assertThat(manageMatches.listMine(STRANGER).collectList().block()).isEmpty();
        assertThat(manageMatches.listMine(ORGANISER).collectList().block()).hasSize(1);
    }

    @Test
    void aTeamMemberCanLookButNotChangeAnything() {
        var m = manageMatches.create(ORGANISER, create(null)).block();
        var member = Actor.user(UUID.randomUUID());
        players.store.get(membersB.get(3)).linkTo(member.id(), NOW);

        assertCode(() -> manageMatches.recordToss(member, m.id(), teamA, TossDecision.BAT).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageMatches.setPlayingXi(member, m.id(), teamB, membersB.subList(0, 5)).block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageMatches.addScorer(member, m.id(), "sam@example.com").block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageMatches.updateSettings(member, m.id(), settings(), m.version()).block(), CricketErrorCode.NOT_ALLOWED);
    }

    // ---- toss, XI, scorers, settings -------------------------------------------------------------------------

    @Test
    void anAssignedScorerRecordsTheTossAndTheXisButCannotManageSettingsOrScorers() {
        var m = manageMatches.create(ORGANISER, create(null)).block();
        manageMatches.addScorer(ORGANISER, m.id(), "SAM@example.com").block();

        var tossed = manageMatches.recordToss(SCORER, m.id(), teamB, TossDecision.BOWL).block();
        assertThat(tossed.status()).isEqualTo("TOSS_DONE");
        assertThat(tossed.battingFirstTeamId()).isEqualTo(teamA);
        manageMatches.setPlayingXi(SCORER, m.id(), teamA, membersA).block();
        var ready = manageMatches.setPlayingXi(SCORER, m.id(), teamB, membersB.subList(0, 9)).block();

        assertThat(ready.readyToStart()).isTrue();
        assertThat(ready.teamA().squad()).as("a scorer picks the XI from the whole squad").hasSize(11);
        assertThat(ready.teamB().squad()).hasSize(11);
        assertThat(ready.teamA().playingXi()).hasSize(11);
        assertThat(ready.teamA().playingXi().get(0).battingOrder()).isEqualTo(1);
        assertThat(ready.teamA().playingXi().get(0).name()).isEqualTo("Lions player 0");
        assertThat(ready.teamB().playingXi()).hasSize(9);
        assertCode(() -> manageMatches.addScorer(SCORER, m.id(), "olive@example.com").block(), CricketErrorCode.NOT_ALLOWED);
        assertCode(() -> manageMatches.updateSettings(SCORER, m.id(), settings(), ready.version()).block(), CricketErrorCode.NOT_ALLOWED);
    }

    @Test
    void thePlayingXiMustComeFromThatTeamAndAPlayerCannotBeOnBothSides() {
        var m = manageMatches.create(ORGANISER, create(null)).block();

        assertCode(() -> manageMatches.setPlayingXi(ORGANISER, m.id(), teamA, membersB.subList(0, 5)).block(), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> manageMatches.setPlayingXi(ORGANISER, m.id(), UUID.randomUUID(), membersA).block(), CricketErrorCode.INVALID_TEAMS);
        assertCode(() -> manageMatches.setPlayingXi(ORGANISER, m.id(), teamA, membersA.subList(0, 1)).block(), CricketErrorCode.INVALID_PLAYING_XI);
        assertCode(() -> manageMatches.setPlayingXi(ORGANISER, m.id(), teamA, null).block(), CricketErrorCode.INVALID_PLAYING_XI);
        assertThat(manageMatches.get(ORGANISER, m.id()).block().teamA().playingXi()).isEmpty();
    }

    @Test
    void scorersAreFoundByEmailAndAnUnknownEmailIsRefusedWithAHint() {
        var m = manageMatches.create(ORGANISER, create(null)).block();

        var withScorer = manageMatches.addScorer(ORGANISER, m.id(), "sam@example.com").block();

        assertThat(withScorer.scorers()).singleElement().satisfies(s -> {
            assertThat(s.userId()).isEqualTo(SCORER.id());
            assertThat(s.name()).isEqualTo("Sam Scorer");
            assertThat(s.email()).isEqualTo("sam@example.com");
        });
        assertCode(() -> manageMatches.addScorer(ORGANISER, m.id(), "sam@example.com").block(), CricketErrorCode.SCORER_ALREADY_ADDED);
        assertCode(() -> manageMatches.addScorer(ORGANISER, m.id(), "olive@example.com").block(), CricketErrorCode.SCORER_ALREADY_ADDED);
        assertCode(() -> manageMatches.addScorer(ORGANISER, m.id(), "nobody@example.com").block(), CricketErrorCode.USER_NOT_REGISTERED);
        assertCode(() -> manageMatches.addScorer(ORGANISER, m.id(), " ").block(), CricketErrorCode.VALIDATION_ERROR);
        assertThat(manageMatches.removeScorer(ORGANISER, m.id(), SCORER.id()).block().scorers()).isEmpty();
        assertCode(() -> manageMatches.removeScorer(ORGANISER, m.id(), SCORER.id()).block(), CricketErrorCode.NOT_A_SCORER);
        assertCode(() -> manageMatches.get(SCORER, m.id()).block(), CricketErrorCode.MATCH_NOT_FOUND);
    }

    @Test
    void settingsNeedTheVersionTheCreatorSawAndRevalidate() {
        var m = manageMatches.create(ORGANISER, create(null)).block();
        var changed = new Match.Settings(10, BallType.TENNIS, new MatchRules(6, 8, 2, 2, 2, false, true), null, "Goa", "Ravi");

        var updated = manageMatches.updateSettings(ORGANISER, m.id(), changed, m.version()).block();

        assertThat(updated.overs()).isEqualTo(10);
        assertThat(updated.ballType()).isEqualTo(BallType.TENNIS);
        assertThat(updated.rules().lastManStands()).isTrue();
        assertThat(updated.city()).isEqualTo("Goa");
        assertCode(() -> manageMatches.updateSettings(ORGANISER, m.id(), changed, m.version()).block(), CricketErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        assertCode(() -> manageMatches.updateSettings(ORGANISER, m.id(), new Match.Settings(0, BallType.TENNIS, null, null, null, null), updated.version()).block(), CricketErrorCode.VALIDATION_ERROR);
    }

    @Test
    void theTossCanBeCorrectedBeforeTheStart() {
        var m = manageMatches.create(ORGANISER, create(null)).block();

        manageMatches.recordToss(ORGANISER, m.id(), teamA, TossDecision.BAT).block();
        var corrected = manageMatches.recordToss(ORGANISER, m.id(), teamB, TossDecision.BAT).block();

        assertThat(corrected.toss().winnerTeamId()).isEqualTo(teamB);
        assertThat(corrected.battingFirstTeamId()).isEqualTo(teamB);
        assertCode(() -> manageMatches.recordToss(ORGANISER, m.id(), UUID.randomUUID(), TossDecision.BAT).block(), CricketErrorCode.INVALID_TOSS);
    }
}
