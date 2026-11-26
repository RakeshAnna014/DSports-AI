package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.ScoringCommand.ChangeBowlerMidOver;
import com.dsports.cricket.domain.rules.ScoringCommand.Deliver;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import com.dsports.cricket.domain.rules.ScoringCommand.StartInnings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Test fixture: an innings being scored. Every command is applied through the engine, so a test reads like a scorer's evening. {@code ok} insists the
 * command is accepted; {@code rej} insists it is rejected for a named rule and that the state did not change.
 */
final class Game {

    static final UUID LIONS = new UUID(1, 1);
    static final UUID TIGERS = new UUID(2, 2);

    final List<UUID> bat;
    final List<UUID> bowl;
    final Map<UUID, String> names = new HashMap<>();
    final List<ScoringCommand> log = new java.util.ArrayList<>();
    InningsState s = InningsState.notStarted();

    Game(MatchRules rules, int overs, int batters, int bowlers, Integer target) {
        this(rules, overs, batters, bowlers, target, LIONS, TIGERS);
    }

    Game(MatchRules rules, int overs, int batters, int bowlers, Integer target, UUID battingTeam, UUID bowlingTeam) {
        bat = IntStream.rangeClosed(1, batters).mapToObj(i -> new UUID(10, i)).toList();
        bowl = IntStream.rangeClosed(1, bowlers).mapToObj(i -> new UUID(20, i)).toList();
        for (int i = 0; i < batters; i++) {
            names.put(bat.get(i), "bat" + (i + 1));
        }
        for (int i = 0; i < bowlers; i++) {
            names.put(bowl.get(i), "bowl" + (i + 1));
        }
        ok(new StartInnings(rules, overs, battingTeam, bowlingTeam, bat, bowl, bat.get(0), bat.get(1), bowl.get(0), target));
    }

    static Game standard(int overs) {
        return new Game(MatchRules.standard(), overs, 11, 5, null);
    }

    static MatchRules rules(int ballsPerOver, int playersPerSide, Integer maxOvers, int widePenalty, int noBallPenalty, boolean freeHit, boolean lastManStands) {
        return new MatchRules(ballsPerOver, playersPerSide, maxOvers, widePenalty, noBallPenalty, freeHit, lastManStands);
    }

    static MatchRules withPlayers(int players) {
        return rules(6, players, null, 1, 1, true, false);
    }

    /** The nth batter of the batting XI (1 is the opening striker, 2 the opening non-striker). */
    UUID b(int n) {
        return bat.get(n - 1);
    }

    /** The nth bowler of the bowling XI (1 opens the bowling). */
    UUID f(int n) {
        return bowl.get(n - 1);
    }

    Game ok(ScoringCommand command) {
        var outcome = ScoringEngine.apply(s, command);
        if (outcome instanceof Outcome.Rejected r) {
            return fail("Expected " + command + " to be accepted but it was rejected: " + r.violation() + " (" + r.message() + ")");
        }
        s = ((Outcome.Accepted) outcome).state();
        log.add(command);
        return this;
    }

    Game rej(ScoringCommand command, RuleViolation expected) {
        var before = s;
        var outcome = ScoringEngine.apply(s, command);
        assertThat(outcome).as("outcome of %s", command).isInstanceOf(Outcome.Rejected.class);
        assertThat(((Outcome.Rejected) outcome).violation()).isEqualTo(expected);
        assertThat(s).as("a rejected command leaves the state alone").isEqualTo(before);
        return this;
    }

    Game ball(Delivery delivery) {
        return ok(new Deliver(delivery));
    }

    Game rejBall(Delivery delivery, RuleViolation expected) {
        return rej(new Deliver(delivery), expected);
    }

    Game dots(int n) {
        for (int i = 0; i < n; i++) {
            ball(Delivery.dot());
        }
        return this;
    }

    Game batter(int n) {
        return ok(new SelectBatter(b(n)));
    }

    Game bowler(int n) {
        return ok(new SelectBowler(f(n)));
    }

    Game changeBowler(int n) {
        return ok(new ChangeBowlerMidOver(f(n)));
    }

    /** Bowls a whole over of dots by the current bowler, then picks the next bowler. */
    Game dotOverThen(int nextBowler) {
        dots(s.rules().ballsPerOver());
        return bowler(nextBowler);
    }

    String score() {
        return s.runs() + "/" + s.wickets();
    }

    String name(UUID id) {
        return id == null ? null : names.get(id);
    }

    String striker() {
        return name(s.striker());
    }

    String nonStriker() {
        return name(s.nonStriker());
    }

    InningsState.BatterLine line(UUID id) {
        return s.batter(id).orElseThrow();
    }

    InningsState.BowlerLine bowlerLine(int n) {
        return s.bowler(f(n)).orElseThrow();
    }
}
