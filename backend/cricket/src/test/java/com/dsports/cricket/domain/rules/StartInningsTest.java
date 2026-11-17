package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.Status;
import com.dsports.cricket.domain.rules.ScoringCommand.Deliver;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.StartInnings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StartInningsTest {

    private static final List<UUID> BAT = IntStream.rangeClosed(1, 11).mapToObj(i -> new UUID(10, i)).toList();
    private static final List<UUID> BOWL = IntStream.rangeClosed(1, 11).mapToObj(i -> new UUID(20, i)).toList();

    private static StartInnings valid() {
        return new StartInnings(MatchRules.standard(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null);
    }

    private static Outcome start(StartInnings c) {
        return ScoringEngine.apply(InningsState.notStarted(), c);
    }

    private static void assertRejected(StartInnings c, RuleViolation expected) {
        assertThat(start(c)).isInstanceOfSatisfying(Outcome.Rejected.class, r -> assertThat(r.violation()).isEqualTo(expected));
    }

    @Test
    void starts_with_the_openers_and_the_opening_bowler() {
        var s = ((Outcome.Accepted) start(valid())).state();

        assertThat(s.status()).isEqualTo(Status.IN_PROGRESS);
        assertThat(s.striker()).isEqualTo(BAT.get(0));
        assertThat(s.nonStriker()).isEqualTo(BAT.get(1));
        assertThat(s.currentBowler()).isEqualTo(BOWL.get(0));
        assertThat(s.runs()).isZero();
        assertThat(s.wickets()).isZero();
        assertThat(s.batters()).extracting(InningsState.BatterLine::playerId).containsExactly(BAT.get(0), BAT.get(1));
        assertThat(s.batters()).allSatisfy(b -> assertThat(b.status()).isEqualTo(BatterStatus.NOT_OUT));
        assertThat(s.bowlers()).extracting(InningsState.BowlerLine::playerId).containsExactly(BOWL.get(0));
        assertThat(s.yetToBat()).containsExactlyElementsOf(BAT.subList(2, 11));
        assertThat(s.freeHit()).isFalse();
        assertThat(s.needsBatter() || s.needsBowler()).isFalse();
        assertThat(s.oversText()).isEqualTo("0.0");
    }

    @Test
    void a_chase_carries_its_target() {
        var s = ((Outcome.Accepted) start(new StartInnings(MatchRules.standard(), 20, Game.TIGERS, Game.LIONS, BOWL, BAT, BOWL.get(0), BOWL.get(1), BAT.get(0), 143))).state();
        assertThat(s.target()).isEqualTo(143);
    }

    @Test
    void an_innings_starts_only_once() {
        var g = Game.standard(20);
        g.rej(valid(), RuleViolation.INNINGS_ALREADY_STARTED);
    }

    @Test
    void nothing_can_happen_before_the_start() {
        var fresh = InningsState.notStarted();
        for (ScoringCommand c : List.<ScoringCommand>of(new Deliver(Delivery.dot()), new SelectBatter(BAT.get(2)), new ScoringCommand.SelectBowler(BOWL.get(1)),
            new ScoringCommand.ChangeBowlerMidOver(BOWL.get(1)), new ScoringCommand.RetireBatter(BAT.get(0), ScoringCommand.RetirementType.HURT),
            new ScoringCommand.EndInnings(InningsState.EndReason.DECLARED), new ScoringCommand.ReviseTarget(10))) {
            assertThat(ScoringEngine.apply(fresh, c)).as("%s", c).isInstanceOfSatisfying(Outcome.Rejected.class,
                r -> assertThat(r.violation()).isEqualTo(RuleViolation.INNINGS_NOT_STARTED));
        }
    }

    @Test
    void refuses_a_setup_that_does_not_make_sense() {
        var c = valid();
        assertRejected(new StartInnings(c.rules(), 0, c.battingTeamId(), c.bowlingTeamId(), BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(c.rules(), 101, c.battingTeamId(), c.bowlingTeamId(), BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.LIONS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(null, 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(Game.rules(3, 11, null, 1, 1, true, false), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
    }

    @Test
    void refuses_playing_xis_that_are_too_small_too_big_duplicated_or_shared() {
        var c = valid();
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT.subList(0, 1), BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(Game.withPlayers(8), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        var duplicated = new ArrayList<>(BAT.subList(0, 4));
        duplicated.add(BAT.get(0));
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, duplicated, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BAT.subList(5, 11), BAT.get(0), BAT.get(1), BAT.get(7), null), RuleViolation.INVALID_SETUP);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, null, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_SETUP);
    }

    @Test
    void refuses_openers_who_are_the_same_or_not_in_the_batting_xi() {
        var c = valid();
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(0), BOWL.get(0), null), RuleViolation.INVALID_OPENERS);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BOWL.get(0), BOWL.get(1), null), RuleViolation.INVALID_OPENERS);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, null, BAT.get(1), BOWL.get(0), null), RuleViolation.INVALID_OPENERS);
    }

    @Test
    void refuses_an_opening_bowler_from_outside_the_bowling_xi_and_a_silly_target() {
        var c = valid();
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BAT.get(5), null), RuleViolation.BOWLER_NOT_IN_XI);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), null, null), RuleViolation.BOWLER_NOT_IN_XI);
        assertRejected(new StartInnings(c.rules(), 20, Game.LIONS, Game.TIGERS, BAT, BOWL, BAT.get(0), BAT.get(1), BOWL.get(0), 0), RuleViolation.INVALID_TARGET);
    }

    @Test
    void a_small_side_is_fine() {
        var g = new Game(Game.withPlayers(3), 5, 3, 3, null);
        assertThat(g.s.yetToBat()).containsExactly(g.b(3));
    }
}
