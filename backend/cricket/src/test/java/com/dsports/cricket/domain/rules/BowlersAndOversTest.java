package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.ScoringCommand.ChangeBowlerMidOver;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Choosing bowlers, the over limit, maidens, and the mid-over change for an injured bowler. */
class BowlersAndOversTest {

    // ---- the next over -----------------------------------------------------------------------------------------

    @Test
    void after_an_over_a_new_bowler_must_be_named_before_the_next_ball() {
        var g = Game.standard(20).dots(6);

        assertThat(g.s.needsBowler()).isTrue();
        assertThat(g.s.previousOverBowler()).isEqualTo(g.f(1));
        g.rejBall(Delivery.dot(), RuleViolation.NEEDS_NEW_BOWLER);
        g.bowler(2);
        assertThat(g.s.currentBowler()).isEqualTo(g.f(2));
        assertThat(g.s.needsBowler()).isFalse();
        assertThat(g.s.over().deliveries()).isZero();
        g.dots(1);
        assertThat(g.bowlerLine(2).legalBalls()).isEqualTo(1);
    }

    @Test
    void a_bowler_cannot_be_named_in_the_middle_of_an_over_that_way() {
        Game.standard(20).dots(2).rej(new SelectBowler(new UUID(20, 2)), RuleViolation.NO_BOWLER_NEEDED);
    }

    @Test
    void the_bowler_of_the_last_over_cannot_bowl_again_straight_away() {
        var g = Game.standard(20).dots(6);
        g.rej(new SelectBowler(g.f(1)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
    }

    @Test
    void the_new_bowler_must_be_in_the_bowling_xi() {
        var g = Game.standard(20).dots(6);
        g.rej(new SelectBowler(g.b(4)), RuleViolation.BOWLER_NOT_IN_XI);
        g.rej(new SelectBowler(null), RuleViolation.BOWLER_NOT_IN_XI);
    }

    @Test
    void a_bowler_may_return_after_one_over_by_someone_else() {
        var g = Game.standard(20).dotOverThen(2).dotOverThen(1);

        assertThat(g.s.currentBowler()).isEqualTo(g.f(1));
        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
    }

    @Test
    void the_over_limit_is_a_fifth_of_the_match_rounded_up() {
        var g = Game.standard(10);
        g.dotOverThen(2).dotOverThen(1);
        g.dots(6);
        g.rej(new SelectBowler(g.f(1)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.bowler(2);
        g.dots(6);
        g.rej(new SelectBowler(g.f(1)), RuleViolation.BOWLER_LIMIT_REACHED);
        g.rej(new SelectBowler(g.f(2)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.bowler(3);
    }

    @Test
    void the_limit_can_be_set_for_the_match() {
        var g = new Game(Game.rules(6, 11, 1, 1, 1, true, false), 20, 11, 5, null);
        g.dotOverThen(2);
        g.dots(6);
        g.rej(new SelectBowler(g.f(1)), RuleViolation.BOWLER_LIMIT_REACHED);
    }

    @Test
    void when_nobody_is_under_the_limit_the_limit_is_waived_so_the_innings_can_go_on() {
        var g = new Game(Game.withPlayers(11), 4, 11, 2, null);
        g.dotOverThen(2);
        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
        g.dots(6);
        g.rej(new SelectBowler(g.f(2)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.bowler(1);
        assertThat(ScoringEngine.eligibleBowlers(Game.standard(4).s)).hasSize(5);
    }

    // ---- maidens and credited overs ----------------------------------------------------------------------------

    @Test
    void an_over_without_runs_off_the_bat_wides_or_no_balls_is_a_maiden_even_with_byes() {
        var g = Game.standard(20).dots(3).ball(Delivery.bye(1)).ball(Delivery.legBye(1)).ball(Delivery.dot());

        assertThat(g.bowlerLine(1).maidens()).isEqualTo(1);
        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
    }

    @Test
    void runs_a_wide_or_a_no_ball_stop_the_over_being_a_maiden() {
        assertThat(Game.standard(20).dots(5).ball(Delivery.runs(1)).bowlerLine(1).maidens()).isZero();
        assertThat(Game.standard(20).ball(Delivery.wide(0)).dots(6).bowlerLine(1).maidens()).isZero();
        assertThat(Game.standard(20).ball(Delivery.noBall(0)).dots(6).bowlerLine(1).maidens()).isZero();
        assertThat(Game.standard(20).ball(Delivery.noBall(0)).dots(6).bowlerLine(1).creditedOvers()).isEqualTo(1);
    }

    @Test
    void the_next_over_starts_a_fresh_maiden_count() {
        var g = Game.standard(20).dots(5).ball(Delivery.runs(1)).bowler(2).dots(6);

        assertThat(g.bowlerLine(1).maidens()).isZero();
        assertThat(g.bowlerLine(2).maidens()).isEqualTo(1);
    }

    @Test
    void a_part_over_at_the_end_of_an_innings_is_not_credited() {
        var g = new Game(Game.withPlayers(2), 5, 2, 2, null).ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)));

        assertThat(g.s.isComplete()).isTrue();
        assertThat(g.bowlerLine(1).creditedOvers()).isZero();
        assertThat(g.bowlerLine(1).legalBalls()).isEqualTo(1);
    }

    // ---- the mid-over change -----------------------------------------------------------------------------------

    @Test
    void an_injured_bowler_is_replaced_and_the_over_counts_for_the_one_who_bowled_most() {
        var g = Game.standard(20).ball(Delivery.runs(1)).ball(Delivery.runs(1));
        g.changeBowler(2);
        assertThat(g.s.currentBowler()).isEqualTo(g.f(2));
        g.dots(4);

        assertThat(g.s.needsBowler()).isTrue();
        assertThat(g.bowlerLine(1).legalBalls()).isEqualTo(2);
        assertThat(g.bowlerLine(2).legalBalls()).isEqualTo(4);
        assertThat(g.bowlerLine(1).creditedOvers()).isZero();
        assertThat(g.bowlerLine(2).creditedOvers()).isEqualTo(1);
        assertThat(g.bowlerLine(1).runs()).isEqualTo(2);
        assertThat(g.s.previousOverBowler()).isEqualTo(g.f(2));
    }

    @Test
    void if_the_first_bowler_bowled_more_he_gets_the_over() {
        var g = Game.standard(20).dots(4);
        g.changeBowler(2).dots(2);

        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
        assertThat(g.bowlerLine(2).creditedOvers()).isZero();
        assertThat(g.bowlerLine(2).legalBalls()).isEqualTo(2);
    }

    @Test
    void a_three_three_tie_goes_to_the_bowler_who_started_the_over() {
        var g = Game.standard(20).dots(3);
        g.changeBowler(2).dots(3);

        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
        assertThat(g.bowlerLine(2).creditedOvers()).isZero();
    }

    @Test
    void a_starter_who_bowled_only_wides_gives_the_over_to_the_replacement() {
        var g = Game.standard(20).ball(Delivery.wide(0)).ball(Delivery.wide(0));
        g.changeBowler(2).dots(6);

        assertThat(g.bowlerLine(1).creditedOvers()).isZero();
        assertThat(g.bowlerLine(1).legalBalls()).isZero();
        assertThat(g.bowlerLine(1).wides()).isEqualTo(2);
        assertThat(g.bowlerLine(2).creditedOvers()).isEqualTo(1);
    }

    @Test
    void the_replaced_bowler_may_bowl_again_later_and_only_the_one_who_finished_the_over_is_barred_from_the_next() {
        var g = Game.standard(20).dots(2);
        g.changeBowler(2).dots(4);

        g.rej(new SelectBowler(g.f(2)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.bowler(1);
        assertThat(g.s.currentBowler()).isEqualTo(g.f(1));
        g.dots(6);
        g.bowler(2);
        assertThat(g.bowlerLine(1).creditedOvers()).isEqualTo(1);
        assertThat(g.bowlerLine(2).creditedOvers()).isEqualTo(1);
    }

    @Test
    void a_maiden_over_with_a_change_is_a_maiden_for_the_bowler_who_gets_the_over() {
        var g = Game.standard(20).dots(2);
        g.changeBowler(2).dots(4);

        assertThat(g.bowlerLine(2).maidens()).isEqualTo(1);
        assertThat(g.bowlerLine(1).maidens()).isZero();
    }

    @Test
    void a_change_needs_a_ball_to_have_been_bowled_and_a_different_bowler() {
        var g = Game.standard(20);
        g.rej(new ChangeBowlerMidOver(g.f(2)), RuleViolation.NO_DELIVERY_IN_OVER);
        g.ball(Delivery.wide(0));
        g.rej(new ChangeBowlerMidOver(g.f(1)), RuleViolation.SAME_BOWLER);
        g.changeBowler(2);
    }

    @Test
    void a_change_cannot_bring_in_the_last_overs_bowler_someone_already_in_this_over_or_a_stranger() {
        var g = Game.standard(20).dotOverThen(2).dots(1);

        g.rej(new ChangeBowlerMidOver(g.f(1)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.rej(new ChangeBowlerMidOver(g.b(5)), RuleViolation.BOWLER_NOT_IN_XI);
        g.rej(new ChangeBowlerMidOver(null), RuleViolation.BOWLER_NOT_IN_XI);
        g.changeBowler(3);
        g.rej(new ChangeBowlerMidOver(g.f(2)), RuleViolation.BOWLER_ALREADY_BOWLED_THIS_OVER);
    }

    @Test
    void a_change_cannot_bring_in_a_bowler_who_has_reached_the_limit() {
        var g = new Game(Game.rules(6, 11, 1, 1, 1, true, false), 20, 11, 5, null);
        g.dotOverThen(2).dotOverThen(3).dots(1);

        g.rej(new ChangeBowlerMidOver(g.f(1)), RuleViolation.BOWLER_LIMIT_REACHED);
        g.changeBowler(4);
    }

    @Test
    void a_change_is_refused_while_a_new_batter_or_a_new_bowler_is_still_to_be_named() {
        var afterWicket = Game.standard(20).ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)));
        afterWicket.rej(new ChangeBowlerMidOver(afterWicket.f(2)), RuleViolation.NEEDS_NEW_BATTER);

        var afterOver = Game.standard(20).dots(6);
        afterOver.rej(new ChangeBowlerMidOver(afterOver.f(2)), RuleViolation.NEEDS_NEW_BOWLER);
    }

    @Test
    void the_free_hit_survives_a_change_of_bowler() {
        var g = Game.standard(20).ball(Delivery.noBall(0));
        g.changeBowler(2);

        assertThat(g.s.freeHit()).isTrue();
        g.rejBall(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
    }

    @Test
    void who_may_bowl_is_reported_without_changing_anything() {
        var g = Game.standard(20).dots(6);

        assertThat(ScoringEngine.eligibleBowlers(g.s)).doesNotContain(g.f(1)).hasSize(4);
        assertThat(ScoringEngine.eligibleBowlers(g.s, Set.of(g.f(2)))).doesNotContain(g.f(1), g.f(2)).hasSize(3);
    }
}
