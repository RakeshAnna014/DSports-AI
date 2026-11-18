package com.dsports.cricket.application;

import com.dsports.cricket.application.port.CustomerDirectoryPort;
import com.dsports.cricket.application.result.ScoringView;
import com.dsports.cricket.application.usecase.Actor;
import com.dsports.cricket.application.usecase.ScoringUseCase;
import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.Team;
import com.dsports.cricket.domain.model.TeamStatus;
import com.dsports.cricket.domain.model.TossDecision;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.dsports.cricket.domain.scoring.GoldenMatch.lion;
import static com.dsports.cricket.domain.scoring.GoldenMatch.tiger;

/**
 * A match ready to score: Lions (created by the organiser) bat first against Tigers (created by the opponent), two overs, players with the ids the golden
 * match uses. Actors: the organiser, an added scorer, the opponent (who has a team in the match but cannot score), a stranger and an admin.
 */
final class ScoringFixture {

    static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");
    static final Actor ORGANISER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    static final Actor SCORER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c2"));
    static final Actor OPPONENT = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
    static final Actor STRANGER = Actor.user(UUID.fromString("00000000-0000-0000-0000-0000000000c4"));
    static final Actor ADMIN = new Actor(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), true);

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final Fakes.Players players = new Fakes.Players();
    final Fakes.Teams teams = new Fakes.Teams();
    final Fakes.Matches matches = new Fakes.Matches();
    final Fakes.Directory directory = new Fakes.Directory();
    final FakeScoringStore store = new FakeScoringStore();
    final MutableClock clock = new MutableClock(T0);
    final ScoringUseCase useCase;
    final Match match;
    int seq;

    ScoringFixture() {
        this(2, MatchRules.standard(), 11, 11, true);
    }

    ScoringFixture(int overs, MatchRules rules, int lions, int tigers, boolean ready) {
        teams.players = players;
        matches.teams = teams;
        directory.people.put(ORGANISER.id(), new CustomerDirectoryPort.Person("Olive Organiser", "olive@example.com"));
        directory.people.put(SCORER.id(), new CustomerDirectoryPort.Person("Sam Scorer", "sam@example.com"));
        var lionMembers = addTeam(GoldenMatch.LIONS, "Lions", ORGANISER.id(), 10, "Lion ", lions);
        var tigerMembers = addTeam(GoldenMatch.TIGERS, "Tigers", OPPONENT.id(), 20, "Tiger ", tigers);
        match = Match.create(ORGANISER.id(), GoldenMatch.LIONS, GoldenMatch.TIGERS, new Match.Settings(overs, BallType.TENNIS, rules, null, null, null), null, T0);
        match.addScorer(SCORER.id(), T0);
        if (ready) {
            match.recordToss(GoldenMatch.LIONS, TossDecision.BAT, T0);
            match.setPlayingXi(GoldenMatch.LIONS, lionMembers, java.util.Set.copyOf(lionMembers), T0);
            match.setPlayingXi(GoldenMatch.TIGERS, tigerMembers, java.util.Set.copyOf(tigerMembers), T0);
        }
        matches.store.put(match.getId(), match);
        useCase = new ScoringUseCase(matches, teams, players, directory, store, clock);
    }

    private List<UUID> addTeam(UUID teamId, String name, UUID creator, long idSpace, String playerPrefix, int size) {
        var ids = IntStream.rangeClosed(1, size).mapToObj(i -> new UUID(idSpace, i)).toList();
        var members = new java.util.ArrayList<Team.Member>();
        for (int i = 0; i < ids.size(); i++) {
            players.store.put(ids.get(i), Player.reconstitute(ids.get(i), new Player.Details(playerPrefix + (i + 1), null, null, null, null, null), null, creator, 1, T0, T0));
            members.add(new Team.Member(ids.get(i), i == 0));
        }
        teams.store.put(teamId, Team.reconstitute(teamId, name, "Chennai", TeamStatus.ACTIVE, creator, members, 1, T0, T0));
        return ids;
    }

    UUID id() {
        return match.getId();
    }

    /** Scores one command as the organiser from the current seq, with a fresh client event id. */
    ScoringView.Outcome score(ScoreCommand command) {
        return score(ORGANISER, command);
    }

    ScoringView.Outcome score(Actor actor, ScoreCommand command) {
        var outcome = useCase.submit(actor, id(), UUID.randomUUID(), seq, command).block();
        seq = outcome.eventSeq();
        return outcome;
    }

    ScoringView.Outcome scoreAll(List<ScoreCommand> commands) {
        ScoringView.Outcome last = null;
        for (var command : commands) {
            last = score(command);
        }
        return last;
    }

    ScoringView.Outcome undo(ScoringLog.UndoMode mode) {
        var outcome = useCase.undo(ORGANISER, id(), UUID.randomUUID(), seq, mode).block();
        seq = outcome.eventSeq();
        return outcome;
    }

    ScoringView view() {
        return useCase.state(ORGANISER, id()).block();
    }

    static ScoreCommand lionsOpen() {
        return new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1));
    }
}
