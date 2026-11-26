package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.ScoringCommand.EndInnings;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.RetirementType;
import com.dsports.cricket.domain.rules.ScoringCommand.ReviseTarget;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InningsEndAndResultTest {

    private static Game chase(int target, int overs) {
        return new Game(Game.withPlayers(11), overs, 11, 5, target, Game.TIGERS, Game.LIONS);
    }

    /** A one-over first innings that made 10 runs. */
    private static Game completedFirst() {
        var g = new Game(Game.withPlayers(11), 1, 11, 5, null).ball(Delivery.boundary(6)).ball(Delivery.boundary(4)).dots(4);
        assertThat(g.s.runs()).isEqualTo(10);
        assertThat(g.s.isComplete()).isTrue();
        return g;
    }

    // ---- the ways an innings ends ------------------------------------------------------------------------------

    @Test
    void the_innings_ends_when_the_last_over_is_bowled_with_no_new_bowler_needed() {
        var g = new Game(Game.withPlayers(11), 2, 11, 5, null).dotOverThen(2);
        assertThat(g.s.isComplete()).isFalse();
        g.dots(6);

        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.needsBowler()).isFalse();
        assertThat(g.s.oversText()).isEqualTo("2.0");
        g.rejBall(Delivery.dot(), RuleViolation.INNINGS_COMPLETE);
        g.rej(new SelectBowler(g.f(3)), RuleViolation.INNINGS_COMPLETE);
    }

    @Test
    void a_wide_or_no_ball_in_the_last_over_does_not_end_it() {
        var g = new Game(Game.withPlayers(11), 1, 11, 5, null).dots(5).ball(Delivery.wide(0)).ball(Delivery.noBall(0));

        assertThat(g.s.isComplete()).isFalse();
        g.ball(Delivery.dot());
        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
    }

    @Test
    void the_scorer_can_declare_and_nothing_can_be_scored_afterwards() {
        var g = Game.standard(20).ball(Delivery.runs(1)).ok(new EndInnings(EndReason.DECLARED));

        assertThat(g.s.endReason()).isEqualTo(EndReason.DECLARED);
        g.rejBall(Delivery.dot(), RuleViolation.INNINGS_COMPLETE);
        g.rej(new EndInnings(EndReason.DECLARED), RuleViolation.INNINGS_COMPLETE);
        g.rej(new RetireBatter(g.b(1), RetirementType.HURT), RuleViolation.INNINGS_COMPLETE);
    }

    @Test
    void the_scorer_cannot_end_an_innings_as_all_out_or_overs_complete() {
        var g = Game.standard(20);
        g.rej(new EndInnings(EndReason.ALL_OUT), RuleViolation.CANNOT_END_YET);
        g.rej(new EndInnings(EndReason.OVERS_COMPLETE), RuleViolation.CANNOT_END_YET);
        g.rej(new EndInnings(EndReason.TARGET_REACHED), RuleViolation.CANNOT_END_YET);
        g.rej(new EndInnings(null), RuleViolation.CANNOT_END_YET);
    }

    // ---- chasing a target --------------------------------------------------------------------------------------

    @Test
    void reaching_the_target_ends_the_innings_at_once_even_in_the_middle_of_an_over() {
        var g = chase(5, 20).ball(Delivery.boundary(4));
        assertThat(g.s.isComplete()).isFalse();
        g.ball(Delivery.runs(1));

        assertThat(g.s.endReason()).isEqualTo(EndReason.TARGET_REACHED);
        assertThat(g.s.legalBalls()).isEqualTo(2);
        assertThat(g.s.needsBowler()).isFalse();
        g.rejBall(Delivery.dot(), RuleViolation.INNINGS_COMPLETE);
    }

    @Test
    void a_wide_can_win_the_chase() {
        var g = chase(1, 20).ball(Delivery.wide(0));

        assertThat(g.s.endReason()).isEqualTo(EndReason.TARGET_REACHED);
    }

    @Test
    void reaching_the_target_on_the_ball_that_bowls_the_side_out_is_a_win() {
        var g = new Game(Game.withPlayers(3), 20, 3, 2, 1, Game.TIGERS, Game.LIONS);
        g.ok(new RetireBatter(g.b(1), RetirementType.OUT)).batter(3);
        g.ball(Delivery.runs(1).out(Dismissal.runOut(new UUID(10, 2), End.BOWLERS_END)));

        assertThat(g.s.wickets()).isEqualTo(2);
        assertThat(g.s.endReason()).isEqualTo(EndReason.TARGET_REACHED);
    }

    @Test
    void being_all_out_one_short_is_all_out() {
        var g = new Game(Game.withPlayers(2), 20, 2, 2, 5, Game.TIGERS, Game.LIONS).ball(Delivery.boundary(4))
            .ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED)));

        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
        assertThat(g.s.runs()).isEqualTo(4);
    }

    @Test
    void the_target_can_be_revised_by_hand_and_a_lower_one_can_finish_the_chase_at_once() {
        var g = chase(100, 20).ball(Delivery.boundary(6)).ball(Delivery.boundary(6));
        g.ok(new ReviseTarget(60));
        assertThat(g.s.target()).isEqualTo(60);
        assertThat(g.s.isComplete()).isFalse();
        g.ok(new ReviseTarget(12));

        assertThat(g.s.endReason()).isEqualTo(EndReason.TARGET_REACHED);
    }

    @Test
    void the_target_can_be_raised_only_while_the_chase_has_not_been_won() {
        var g = chase(5, 20).ball(Delivery.boundary(6));
        g.rej(new ReviseTarget(9), RuleViolation.TARGET_ALREADY_REACHED);
        g.ok(new ReviseTarget(6));
        assertThat(g.s.target()).isEqualTo(6);
    }

    @Test
    void only_a_chase_has_a_target_to_revise_and_it_must_be_positive() {
        Game.standard(20).rej(new ReviseTarget(10), RuleViolation.NO_TARGET_TO_REVISE);
        chase(10, 20).rej(new ReviseTarget(0), RuleViolation.INVALID_TARGET);
    }

    @Test
    void a_target_can_be_revised_after_the_overs_run_out_which_only_changes_the_result() {
        var g = chase(20, 1).dots(6);
        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        g.ok(new ReviseTarget(1));
        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.target()).isEqualTo(1);
    }

    // ---- the result --------------------------------------------------------------------------------------------

    @Test
    void the_chasing_side_wins_by_the_wickets_in_hand_and_the_balls_left() {
        var first = completedFirst();
        var second = chase(11, 1).ball(Delivery.boundary(6)).ball(Delivery.boundary(4)).ball(Delivery.runs(1));
        var result = MatchResultCalculator.compute(first.s, second.s);

        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
        assertThat(result.winnerTeamId()).isEqualTo(Game.TIGERS);
        assertThat(result.margin()).isEqualTo(10);
        assertThat(result.ballsRemaining()).isEqualTo(3);
    }

    @Test
    void wickets_in_hand_count_every_fallen_wicket_including_a_retirement_out() {
        var first = completedFirst();
        var second = chase(11, 1).ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED))).batter(3);
        second.ok(new RetireBatter(second.b(3), RetirementType.OUT)).batter(4);
        second.ball(Delivery.boundary(6)).ball(Delivery.boundary(6));
        var result = MatchResultCalculator.compute(first.s, second.s);

        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
        assertThat(result.margin()).isEqualTo(8);
    }

    @Test
    void the_first_side_wins_by_the_runs_the_chase_fell_short_by() {
        var first = completedFirst();
        var second = chase(11, 1).ball(Delivery.runs(2)).dots(5);
        var result = MatchResultCalculator.compute(first.s, second.s);

        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_RUNS);
        assertThat(result.winnerTeamId()).isEqualTo(Game.LIONS);
        assertThat(result.margin()).isEqualTo(8);
    }

    @Test
    void level_scores_are_a_tie_with_no_winner() {
        var first = completedFirst();
        var second = chase(11, 1).ball(Delivery.boundary(6)).ball(Delivery.boundary(4)).dots(4);
        var result = MatchResultCalculator.compute(first.s, second.s);

        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.TIE);
        assertThat(result.winnerTeamId()).isNull();
    }

    @Test
    void a_revised_target_decides_the_result() {
        var first = completedFirst();
        var second = chase(11, 1).ball(Delivery.runs(2)).dots(5);
        second.ok(new ReviseTarget(4));
        var short_ = MatchResultCalculator.compute(first.s, second.s);

        assertThat(short_.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_RUNS);
        assertThat(short_.margin()).isEqualTo(1);
        second.ok(new ReviseTarget(3));
        assertThat(MatchResultCalculator.compute(first.s, second.s).type()).isEqualTo(MatchResultCalculator.Type.TIE);
        second.ok(new ReviseTarget(2));
        assertThat(MatchResultCalculator.compute(first.s, second.s).type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
    }

    @Test
    void a_chase_still_in_progress_has_no_result_yet() {
        var first = completedFirst();
        var second = chase(11, 1).dots(2);

        assertThat(MatchResultCalculator.compute(first.s, second.s).type()).isEqualTo(MatchResultCalculator.Type.IN_PROGRESS);
    }

    @Test
    void the_result_needs_a_finished_first_innings_and_a_chase() {
        var unfinished = new Game(Game.withPlayers(11), 1, 11, 5, null).dots(2);
        var second = chase(11, 1);
        assertThatThrownBy(() -> MatchResultCalculator.compute(unfinished.s, second.s)).isInstanceOf(IllegalArgumentException.class);
        var first = completedFirst();
        assertThatThrownBy(() -> MatchResultCalculator.compute(first.s, first.s)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void with_last_man_stands_every_player_is_a_wicket_in_hand() {
        var first = completedFirst();
        var second = new Game(Game.rules(6, 3, null, 1, 1, true, true), 1, 3, 2, 11, Game.TIGERS, Game.LIONS)
            .ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED))).batter(3).ball(Delivery.boundary(6)).ball(Delivery.boundary(6));
        var result = MatchResultCalculator.compute(first.s, second.s);

        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
        assertThat(result.margin()).isEqualTo(2);
    }
}
