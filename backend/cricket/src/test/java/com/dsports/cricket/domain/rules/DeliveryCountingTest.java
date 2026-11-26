package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Delivery.RunsType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** What each kind of ball adds to the score, the batter, the bowler and the extras, and who is on strike afterwards. */
class DeliveryCountingTest {

    @Test
    void a_dot_ball_is_a_legal_ball_faced_by_the_striker() {
        var g = Game.standard(20).ball(Delivery.dot());

        assertThat(g.score()).isEqualTo("0/0");
        assertThat(g.s.legalBalls()).isEqualTo(1);
        assertThat(g.s.oversText()).isEqualTo("0.1");
        assertThat(g.line(g.b(1)).balls()).isEqualTo(1);
        assertThat(g.bowlerLine(1).legalBalls()).isEqualTo(1);
        assertThat(g.s.over().deliveries()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void bat_runs_go_to_the_batter_and_the_bowler_and_an_odd_number_swaps_the_batters(int runs) {
        var g = Game.standard(20).ball(Delivery.runs(runs));

        assertThat(g.s.runs()).isEqualTo(runs);
        assertThat(g.line(g.b(1)).runs()).isEqualTo(runs);
        assertThat(g.bowlerLine(1).runs()).isEqualTo(runs);
        assertThat(g.s.extras().total()).isZero();
        assertThat(g.striker()).isEqualTo(runs % 2 == 1 ? "bat2" : "bat1");
        assertThat(g.nonStriker()).isEqualTo(runs % 2 == 1 ? "bat1" : "bat2");
    }

    @Test
    void more_than_seven_bat_runs_or_a_negative_number_is_refused() {
        Game.standard(20).rejBall(Delivery.runs(8), RuleViolation.RUNS_OUT_OF_RANGE).rejBall(Delivery.runs(-1), RuleViolation.RUNS_OUT_OF_RANGE)
            .rejBall(Delivery.wide(8), RuleViolation.RUNS_OUT_OF_RANGE).rejBall(Delivery.bye(8), RuleViolation.RUNS_OUT_OF_RANGE);
    }

    @Test
    void a_boundary_four_or_six_is_counted_as_such_and_does_not_swap_the_batters() {
        var g = Game.standard(20).ball(Delivery.boundary(4)).ball(Delivery.boundary(6));

        assertThat(g.line(g.b(1)).runs()).isEqualTo(10);
        assertThat(g.line(g.b(1)).fours()).isEqualTo(1);
        assertThat(g.line(g.b(1)).sixes()).isEqualTo(1);
        assertThat(g.striker()).isEqualTo("bat1");
    }

    @Test
    void four_runs_run_are_not_a_boundary() {
        var g = Game.standard(20).ball(Delivery.runs(4));

        assertThat(g.line(g.b(1)).runs()).isEqualTo(4);
        assertThat(g.line(g.b(1)).fours()).isZero();
    }

    @Test
    void a_boundary_must_be_a_four_or_a_six_off_the_bat_and_cannot_be_a_wicket() {
        Game.standard(20)
            .rejBall(Delivery.boundary(3), RuleViolation.INVALID_BOUNDARY)
            .rejBall(new Delivery(Kind.NORMAL, 6, RunsType.BYE, true, null), RuleViolation.INVALID_BOUNDARY)
            .rejBall(new Delivery(Kind.WIDE, 6, null, true, null), RuleViolation.INVALID_BOUNDARY)
            .rejBall(Delivery.boundary(4).out(Dismissal.runOut(new java.util.UUID(10, 1), Dismissal.End.BATTING_END)), RuleViolation.INVALID_BOUNDARY);
    }

    @Test
    void four_byes_run_to_the_boundary_are_byes() {
        var g = Game.standard(20).ball(new Delivery(Kind.NORMAL, 4, RunsType.BYE, true, null));

        assertThat(g.s.extras().byes()).isEqualTo(4);
        assertThat(g.line(g.b(1)).fours()).isZero();
        assertThat(g.striker()).isEqualTo("bat1");
    }

    @Test
    void a_wide_is_the_penalty_charged_to_the_bowler_and_is_neither_legal_nor_faced() {
        var g = Game.standard(20).ball(Delivery.wide(0));

        assertThat(g.score()).isEqualTo("1/0");
        assertThat(g.s.extras().wides()).isEqualTo(1);
        assertThat(g.s.legalBalls()).isZero();
        assertThat(g.s.over().deliveries()).isEqualTo(1);
        assertThat(g.s.over().legalBalls()).isZero();
        assertThat(g.line(g.b(1)).balls()).isZero();
        assertThat(g.bowlerLine(1).runs()).isEqualTo(1);
        assertThat(g.bowlerLine(1).wides()).isEqualTo(1);
        assertThat(g.striker()).isEqualTo("bat1");
    }

    @Test
    void wide_runs_run_add_to_the_penalty_and_an_odd_number_of_them_swaps_the_batters() {
        var g = Game.standard(20).ball(Delivery.wide(1));

        assertThat(g.score()).isEqualTo("2/0");
        assertThat(g.s.extras().wides()).isEqualTo(2);
        assertThat(g.striker()).isEqualTo("bat2");
        g.ball(Delivery.wide(2));
        assertThat(g.score()).isEqualTo("5/0");
        assertThat(g.striker()).isEqualTo("bat2");
    }

    @Test
    void a_wide_that_goes_to_the_boundary_is_the_penalty_plus_four() {
        var g = Game.standard(20).ball(new Delivery(Kind.WIDE, 4, null, true, null));

        assertThat(g.s.extras().wides()).isEqualTo(5);
        assertThat(g.striker()).isEqualTo("bat1");
    }

    @Test
    void the_penalties_can_be_set_for_the_match() {
        var g = new Game(Game.rules(6, 11, null, 2, 0, true, false), 20, 11, 5, null);
        g.ball(Delivery.wide(0));
        assertThat(g.score()).isEqualTo("2/0");
        g.ball(Delivery.noBall(0));
        assertThat(g.score()).isEqualTo("2/0");
        assertThat(g.s.extras().noBalls()).isZero();
        assertThat(g.s.freeHit()).isTrue();
    }

    @Test
    void a_no_ball_is_the_penalty_plus_the_bat_runs_and_is_faced_but_not_legal() {
        var g = Game.standard(20).ball(Delivery.noBall(2));

        assertThat(g.score()).isEqualTo("3/0");
        assertThat(g.s.extras().noBalls()).isEqualTo(1);
        assertThat(g.s.legalBalls()).isZero();
        assertThat(g.line(g.b(1)).runs()).isEqualTo(2);
        assertThat(g.line(g.b(1)).balls()).isEqualTo(1);
        assertThat(g.bowlerLine(1).runs()).isEqualTo(3);
        assertThat(g.bowlerLine(1).noBalls()).isEqualTo(1);
        assertThat(g.striker()).isEqualTo("bat1");
    }

    @Test
    void a_no_ball_and_a_six_is_seven_and_with_a_two_run_penalty_it_is_eight() {
        var standard = Game.standard(20).ball(Delivery.noBallBoundary(6));
        assertThat(standard.score()).isEqualTo("7/0");

        var heavy = new Game(Game.rules(6, 11, null, 1, 2, true, false), 20, 11, 5, null).ball(Delivery.noBallBoundary(6));
        assertThat(heavy.score()).isEqualTo("8/0");
        assertThat(heavy.line(heavy.b(1)).runs()).isEqualTo(6);
        assertThat(heavy.line(heavy.b(1)).sixes()).isEqualTo(1);
        assertThat(heavy.bowlerLine(1).runs()).isEqualTo(8);
    }

    @Test
    void byes_on_a_no_ball_are_byes_and_only_the_penalty_is_charged_to_the_bowler() {
        var g = Game.standard(20).ball(Delivery.noBall(1, RunsType.BYE));

        assertThat(g.score()).isEqualTo("2/0");
        assertThat(g.s.extras().noBalls()).isEqualTo(1);
        assertThat(g.s.extras().byes()).isEqualTo(1);
        assertThat(g.line(g.b(1)).runs()).isZero();
        assertThat(g.line(g.b(1)).balls()).isEqualTo(1);
        assertThat(g.bowlerLine(1).runs()).isEqualTo(1);
        assertThat(g.striker()).isEqualTo("bat2");
    }

    @Test
    void byes_and_leg_byes_are_extras_off_a_legal_ball_the_striker_faced_and_not_charged_to_the_bowler() {
        var g = Game.standard(20).ball(Delivery.bye(2)).ball(Delivery.legBye(1));

        assertThat(g.score()).isEqualTo("3/0");
        assertThat(g.s.extras().byes()).isEqualTo(2);
        assertThat(g.s.extras().legByes()).isEqualTo(1);
        assertThat(g.s.legalBalls()).isEqualTo(2);
        assertThat(g.line(g.b(1)).balls()).isEqualTo(2);
        assertThat(g.line(g.b(2)).balls()).isZero();
        assertThat(g.line(g.b(1)).runs() + g.line(g.b(2)).runs()).isZero();
        assertThat(g.bowlerLine(1).runs()).isZero();
        assertThat(g.striker()).isEqualTo("bat2");
    }

    @Test
    void the_total_is_always_the_bat_runs_plus_the_extras() {
        var g = Game.standard(20).ball(Delivery.runs(1)).ball(Delivery.wide(0)).ball(Delivery.noBall(3)).ball(Delivery.bye(1)).ball(Delivery.legBye(2)).ball(Delivery.boundary(4));

        int bat = g.s.batters().stream().mapToInt(InningsState.BatterLine::runs).sum();
        assertThat(g.s.runs()).isEqualTo(bat + g.s.extras().total());
        assertThat(g.s.runs()).isEqualTo(1 + 1 + 4 + 1 + 2 + 4);
    }

    // ---- the end of an over -------------------------------------------------------------------------------------

    @Test
    void the_over_ends_on_the_sixth_legal_ball_not_the_sixth_delivery() {
        var g = Game.standard(20).dots(4).ball(Delivery.wide(0)).ball(Delivery.noBall(0)).dots(1);

        assertThat(g.s.over().deliveries()).isEqualTo(7);
        assertThat(g.s.needsBowler()).isFalse();
        g.dots(1);
        assertThat(g.s.needsBowler()).isTrue();
        assertThat(g.s.oversText()).isEqualTo("1.0");
    }

    @Test
    void the_batters_swap_at_the_end_of_an_over() {
        var g = Game.standard(20).dots(6);

        assertThat(g.striker()).isEqualTo("bat2");
        assertThat(g.nonStriker()).isEqualTo("bat1");
    }

    @Test
    void a_single_off_the_last_ball_leaves_the_same_batter_on_strike_for_the_next_over() {
        var g = Game.standard(20).dots(5).ball(Delivery.runs(1));

        assertThat(g.striker()).isEqualTo("bat1");
        assertThat(g.nonStriker()).isEqualTo("bat2");
    }

    @Test
    void a_boundary_off_the_last_ball_swaps_them_once_for_the_new_over() {
        var g = Game.standard(20).dots(5).ball(Delivery.boundary(4));

        assertThat(g.striker()).isEqualTo("bat2");
    }

    @Test
    void nothing_can_be_bowled_until_the_next_bowler_is_chosen() {
        var g = Game.standard(20).dots(6);
        g.rejBall(Delivery.dot(), RuleViolation.NEEDS_NEW_BOWLER);
    }

    @Test
    void overs_are_counted_in_the_balls_per_over_of_the_match() {
        var g = new Game(Game.rules(8, 11, null, 1, 1, true, false), 10, 11, 5, null).dots(8);

        assertThat(g.s.oversText()).isEqualTo("1.0");
        assertThat(g.s.needsBowler()).isTrue();
    }

    @Test
    void economy_is_runs_per_over_of_the_balls_actually_bowled() {
        var g = Game.standard(20).ball(Delivery.runs(2)).ball(Delivery.runs(1));

        assertThat(g.bowlerLine(1).economy(6)).isEqualByComparingTo("9.00");
        assertThat(new InningsState.BowlerLine(g.f(2), 0, 0, 0, 0, 0, 0, 0).economy(6)).isEqualByComparingTo("0.00");
    }
}
