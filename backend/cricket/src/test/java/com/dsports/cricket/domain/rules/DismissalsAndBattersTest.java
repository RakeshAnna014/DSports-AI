package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A wicket falling: who is out, who is credited, where the batters stand, and how the next batter comes in. */
class DismissalsAndBattersTest {

    private static Delivery bowled() {
        return Delivery.dot().out(Dismissal.striker(Type.BOWLED));
    }

    @Test
    void a_bowled_batter_is_out_the_bowler_is_credited_and_the_next_batter_must_be_named() {
        var g = Game.standard(20).ball(bowled());

        assertThat(g.score()).isEqualTo("0/1");
        var line = g.line(g.b(1));
        assertThat(line.status()).isEqualTo(BatterStatus.OUT);
        assertThat(line.dismissal()).isEqualTo(Type.BOWLED);
        assertThat(line.bowlerId()).isEqualTo(g.f(1));
        assertThat(line.balls()).isEqualTo(1);
        assertThat(g.bowlerLine(1).wickets()).isEqualTo(1);
        assertThat(g.s.fallOfWickets()).containsExactly(new InningsState.FallOfWicket(1, 0, g.b(1), Type.BOWLED, 1));
        assertThat(g.s.needsBatter()).isTrue();
        assertThat(g.s.striker()).isNull();
        assertThat(g.nonStriker()).isEqualTo("bat2");
        g.rejBall(Delivery.dot(), RuleViolation.NEEDS_NEW_BATTER);

        g.batter(3);
        assertThat(g.striker()).isEqualTo("bat3");
        assertThat(g.nonStriker()).isEqualTo("bat2");
        assertThat(g.s.needsBatter()).isFalse();
        assertThat(g.s.batters()).extracting(InningsState.BatterLine::playerId).containsExactly(g.b(1), g.b(2), g.b(3));
    }

    @Test
    void the_fall_of_wicket_records_the_score_after_the_ball() {
        var g = Game.standard(20).ball(Delivery.runs(2)).ball(Delivery.runs(1)).ball(bowled());

        assertThat(g.s.fallOfWickets()).containsExactly(new InningsState.FallOfWicket(1, 3, g.b(2), Type.BOWLED, 3));
    }

    @Test
    void caught_lbw_stumped_and_hit_wicket_score_no_runs() {
        for (var type : new Type[]{Type.CAUGHT, Type.LBW, Type.BOWLED, Type.HIT_WICKET}) {
            Game.standard(20).rejBall(Delivery.runs(1).out(Dismissal.striker(type)), RuleViolation.DISMISSAL_RUNS_NOT_ALLOWED);
        }
        Game.standard(20).rejBall(Delivery.wide(1).out(Dismissal.striker(Type.STUMPED)), RuleViolation.DISMISSAL_RUNS_NOT_ALLOWED);
    }

    @Test
    void stumped_on_a_wide_is_a_wicket_for_the_bowler_but_the_ball_is_not_legal_or_faced() {
        var g = Game.standard(20).ball(Delivery.wide(0).out(Dismissal.striker(Type.STUMPED)));

        assertThat(g.score()).isEqualTo("1/1");
        assertThat(g.s.legalBalls()).isZero();
        assertThat(g.line(g.b(1)).balls()).isZero();
        assertThat(g.bowlerLine(1).wickets()).isEqualTo(1);
        assertThat(g.s.fallOfWickets().get(0).legalBalls()).isZero();
    }

    @Test
    void hit_wicket_on_a_wide_is_allowed_and_caught_is_not() {
        Game.standard(20).ball(Delivery.wide(0).out(Dismissal.striker(Type.HIT_WICKET)));
        Game.standard(20).rejBall(Delivery.wide(0).out(Dismissal.striker(Type.CAUGHT)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_WIDE);
    }

    @Test
    void on_a_no_ball_only_run_out_obstructing_and_a_double_hit_are_possible() {
        Game.standard(20).rejBall(Delivery.noBall(0).out(Dismissal.striker(Type.BOWLED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_NO_BALL);
        Game.standard(20).rejBall(Delivery.noBall(0).out(Dismissal.striker(Type.CAUGHT)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_NO_BALL);
        Game.standard(20).rejBall(Delivery.noBall(0).out(Dismissal.striker(Type.STUMPED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_NO_BALL);
        var g = Game.standard(20).ball(Delivery.noBall(2).out(Dismissal.striker(Type.HIT_BALL_TWICE)));

        assertThat(g.score()).isEqualTo("3/1");
        assertThat(g.line(g.b(1)).runs()).isEqualTo(2);
        assertThat(g.line(g.b(1)).bowlerId()).isNull();
        assertThat(g.bowlerLine(1).wickets()).isZero();
    }

    @Test
    void only_the_striker_can_be_bowled_caught_lbw_stumped_hit_wicket_or_hit_the_ball_twice() {
        var g = Game.standard(20);
        for (var type : new Type[]{Type.BOWLED, Type.CAUGHT, Type.LBW, Type.STUMPED, Type.HIT_WICKET, Type.HIT_BALL_TWICE}) {
            g.rejBall(Delivery.dot().out(Dismissal.of(type, g.b(2))), RuleViolation.ONLY_STRIKER_CAN_BE_OUT);
        }
        g.ball(Delivery.dot().out(Dismissal.of(Type.BOWLED, g.b(1))));
        assertThat(g.score()).isEqualTo("0/1");
    }

    // ---- run out ------------------------------------------------------------------------------------------------

    @Test
    void striker_run_out_at_the_batting_end_the_new_batter_takes_strike() {
        var g = Game.standard(20).ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 1), End.BATTING_END)));

        assertThat(g.s.striker()).isNull();
        assertThat(g.nonStriker()).isEqualTo("bat2");
        g.batter(3);
        assertThat(g.striker()).isEqualTo("bat3");
        assertThat(g.line(g.b(1)).dismissal()).isEqualTo(Type.RUN_OUT);
        assertThat(g.line(g.b(1)).bowlerId()).isNull();
        assertThat(g.bowlerLine(1).wickets()).isZero();
        assertThat(g.s.fallOfWickets().get(0).type()).isEqualTo(Type.RUN_OUT);
    }

    @Test
    void striker_run_out_at_the_bowlers_end_the_partner_is_on_strike_and_the_new_batter_is_at_the_other_end() {
        var g = Game.standard(20).ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 1), End.BOWLERS_END)));

        assertThat(g.striker()).isEqualTo("bat2");
        assertThat(g.s.nonStriker()).isNull();
        g.batter(3);
        assertThat(g.striker()).isEqualTo("bat2");
        assertThat(g.nonStriker()).isEqualTo("bat3");
    }

    @Test
    void non_striker_run_out_at_either_end() {
        var atStrikers = Game.standard(20).ball(Delivery.runs(1).out(Dismissal.runOut(new UUID(10, 2), End.BATTING_END)));
        assertThat(atStrikers.score()).isEqualTo("1/1");
        assertThat(atStrikers.line(atStrikers.b(1)).runs()).isEqualTo(1);
        assertThat(atStrikers.s.striker()).isNull();
        assertThat(atStrikers.nonStriker()).isEqualTo("bat1");
        atStrikers.batter(3);
        assertThat(atStrikers.striker()).isEqualTo("bat3");

        var atBowlers = Game.standard(20).ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 2), End.BOWLERS_END)));
        assertThat(atBowlers.striker()).isEqualTo("bat1");
        atBowlers.batter(3);
        assertThat(atBowlers.nonStriker()).isEqualTo("bat3");
    }

    @Test
    void a_run_out_keeps_the_runs_completed_and_they_are_credited_to_the_striker_and_charged_to_the_bowler() {
        var g = Game.standard(20).ball(Delivery.runs(2).out(Dismissal.runOut(new UUID(10, 2), End.BOWLERS_END)));

        assertThat(g.score()).isEqualTo("2/1");
        assertThat(g.line(g.b(1)).runs()).isEqualTo(2);
        assertThat(g.bowlerLine(1).runs()).isEqualTo(2);
        assertThat(g.s.fallOfWickets().get(0).runs()).isEqualTo(2);
    }

    @Test
    void a_run_out_on_a_wide_is_allowed_with_the_penalty_and_the_runs() {
        var g = Game.standard(20).ball(Delivery.wide(1).out(Dismissal.runOut(new UUID(10, 1), End.BATTING_END)));

        assertThat(g.score()).isEqualTo("2/1");
        assertThat(g.s.legalBalls()).isZero();
    }

    @Test
    void a_run_out_needs_the_batter_and_the_end_and_the_batter_must_be_at_the_crease() {
        var g = Game.standard(20);
        g.rejBall(Delivery.dot().out(new Dismissal(Type.RUN_OUT, null, End.BATTING_END)), RuleViolation.DISMISSED_BATTER_REQUIRED);
        g.rejBall(Delivery.dot().out(new Dismissal(Type.RUN_OUT, g.b(1), null)), RuleViolation.RUN_OUT_END_REQUIRED);
        g.rejBall(Delivery.dot().out(Dismissal.runOut(g.b(5), End.BATTING_END)), RuleViolation.BATTER_NOT_AT_CREASE);
        g.rejBall(Delivery.dot().out(new Dismissal(null, g.b(1), null)), RuleViolation.INVALID_DELIVERY);
    }

    @Test
    void obstructing_the_field_can_dismiss_either_batter_and_keeps_the_runs() {
        var g = Game.standard(20).ball(Delivery.runs(1).out(Dismissal.of(Type.OBSTRUCTING_FIELD, new UUID(10, 2))));

        assertThat(g.score()).isEqualTo("1/1");
        assertThat(g.line(g.b(2)).dismissal()).isEqualTo(Type.OBSTRUCTING_FIELD);
        assertThat(g.bowlerLine(1).wickets()).isZero();
        g.batter(3);
        assertThat(g.striker()).isEqualTo("bat3");
        assertThat(g.nonStriker()).isEqualTo("bat1");
        Game.standard(20).rejBall(Delivery.dot().out(new Dismissal(Type.OBSTRUCTING_FIELD, null, null)), RuleViolation.DISMISSED_BATTER_REQUIRED);
    }

    // ---- a wicket on the last ball of an over ------------------------------------------------------------------

    @Test
    void a_wicket_on_the_last_ball_needs_a_new_bowler_and_a_new_batter_and_the_partner_faces_the_next_over() {
        var g = Game.standard(20).dots(5).ball(bowled());

        assertThat(g.s.needsBatter()).isTrue();
        assertThat(g.s.needsBowler()).isTrue();
        assertThat(g.striker()).isEqualTo("bat2");
        g.batter(3).bowler(2);
        assertThat(g.striker()).isEqualTo("bat2");
        assertThat(g.nonStriker()).isEqualTo("bat3");
        g.dots(1);
    }

    @Test
    void a_run_out_on_the_last_ball_is_handled_the_same_way() {
        var g = Game.standard(20).dots(5).ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 1), End.BATTING_END)));

        assertThat(g.striker()).isEqualTo("bat2");
        g.batter(3).bowler(2);
        assertThat(g.nonStriker()).isEqualTo("bat3");
    }

    // ---- naming the next batter ---------------------------------------------------------------------------------

    @Test
    void a_batter_cannot_be_named_when_none_is_needed() {
        Game.standard(20).rej(new SelectBatter(new UUID(10, 3)), RuleViolation.NO_BATTER_NEEDED);
    }

    @Test
    void the_next_batter_must_be_in_the_xi_not_at_the_crease_and_not_already_out() {
        var g = Game.standard(20).ball(bowled());
        g.rej(new SelectBatter(new UUID(99, 99)), RuleViolation.BATTER_NOT_IN_XI);
        g.rej(new SelectBatter(null), RuleViolation.BATTER_NOT_IN_XI);
        g.rej(new SelectBatter(g.b(2)), RuleViolation.BATTER_ALREADY_AT_CREASE);
        g.rej(new SelectBatter(g.b(1)), RuleViolation.BATTER_ALREADY_OUT);
    }

    // ---- all out -------------------------------------------------------------------------------------------------

    @Test
    void the_innings_is_all_out_when_only_one_batter_is_left() {
        var g = new Game(Game.withPlayers(3), 20, 3, 2, null).ball(bowled()).batter(3).ball(bowled());

        assertThat(g.s.isComplete()).isTrue();
        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
        assertThat(g.s.needsBatter()).isFalse();
        assertThat(g.s.wickets()).isEqualTo(2);
        g.rejBall(Delivery.dot(), RuleViolation.INNINGS_COMPLETE);
    }

    @Test
    void a_full_side_is_all_out_after_ten_wickets() {
        var g = Game.standard(20);
        for (int i = 3; i <= 11; i++) {
            g.ball(bowled());
            if (g.s.needsBowler()) {
                g.bowler(g.s.currentBowler().equals(g.f(2)) ? 3 : 2);
            }
            g.batter(i);
        }
        assertThat(g.s.isComplete()).isFalse();
        g.ball(bowled());

        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
        assertThat(g.s.wickets()).isEqualTo(10);
        assertThat(g.s.yetToBat()).isEmpty();
    }

    // ---- last man stands -----------------------------------------------------------------------------------------

    @Test
    void with_last_man_stands_the_last_batter_carries_on_alone_and_the_innings_ends_when_he_is_out() {
        var g = new Game(Game.rules(6, 3, null, 1, 1, true, true), 20, 3, 2, null).ball(bowled()).batter(3).ball(bowled());

        assertThat(g.s.isComplete()).isFalse();
        assertThat(g.s.needsBatter()).isFalse();
        assertThat(g.striker()).isEqualTo("bat2");
        assertThat(g.s.nonStriker()).isNull();

        g.ball(Delivery.runs(1));
        assertThat(g.striker()).isEqualTo("bat2");
        g.dots(3);
        assertThat(g.s.needsBowler()).isTrue();
        g.bowler(2);
        assertThat(g.striker()).isEqualTo("bat2");

        g.ball(bowled());
        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
        assertThat(g.s.wickets()).isEqualTo(3);
    }

    @Test
    void the_lone_batter_can_be_run_out_and_that_ends_the_innings() {
        var g = new Game(Game.rules(6, 2, null, 1, 1, true, true), 20, 2, 2, null)
            .ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 1), End.BATTING_END)));

        assertThat(g.striker()).isEqualTo("bat2");
        g.ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 2), End.BATTING_END)));
        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
    }

    @Test
    void a_dismissal_kind_other_than_run_out_never_needs_the_end() {
        var d = new Delivery(Kind.NORMAL, 0, null, false, Dismissal.striker(Type.LBW));
        Game.standard(20).ball(d);
    }
}
