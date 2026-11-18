package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The optional fielder on a catch, a stumping or a run out, and how a dismissal reads on a scorecard. */
class FielderTest {

    private static Game game() {
        var g = Game.standard(20);
        g.names.put(g.f(1), "Tarun");
        g.names.put(g.f(2), "Uday");
        g.names.put(g.f(3), "Ravi");
        g.names.put(g.b(1), "Arun");
        g.names.put(g.b(2), "Bala");
        return g;
    }

    private static String howOut(Game g, int batter) {
        return Scorecard.howOut(g.line(g.b(batter)), g::name);
    }

    @Test
    void caught_stumped_and_run_out_can_name_a_fielder_from_the_fielding_xi() {
        var g = game();
        g.ball(Delivery.dot().out(Dismissal.striker(Type.CAUGHT).by(g.f(3))));
        assertThat(g.line(g.b(1)).fielderId()).isEqualTo(g.f(3));
        assertThat(howOut(g, 1)).isEqualTo("c Ravi b Tarun");

        var s = game();
        s.ball(Delivery.wide(0).out(Dismissal.striker(Type.STUMPED).by(s.f(3))));
        assertThat(howOut(s, 1)).isEqualTo("st Ravi b Tarun");

        var r = game();
        r.ball(Delivery.runs(1).out(Dismissal.runOut(r.b(1), End.BATTING_END).by(r.f(3))));
        assertThat(howOut(r, 1)).isEqualTo("run out (Ravi)");
        assertThat(r.bowlerLine(1).wickets()).isZero();
    }

    @Test
    void the_fielder_is_optional() {
        var c = game().ball(Delivery.dot().out(Dismissal.striker(Type.CAUGHT)));
        assertThat(howOut(c, 1)).isEqualTo("caught b Tarun");
        var r = game().ball(Delivery.dot().out(Dismissal.runOut(new UUID(10, 1), End.BATTING_END)));
        assertThat(howOut(r, 1)).isEqualTo("run out");
        var s = game().ball(Delivery.wide(0).out(Dismissal.striker(Type.STUMPED)));
        assertThat(howOut(s, 1)).isEqualTo("stumped b Tarun");
    }

    @Test
    void a_bowler_who_catches_his_own_ball_is_caught_and_bowled() {
        var g = game().ball(Delivery.dot().out(Dismissal.striker(Type.CAUGHT).by(new UUID(20, 1))));

        assertThat(howOut(g, 1)).isEqualTo("c & b Tarun");
    }

    @Test
    void other_dismissals_read_as_on_a_scorecard() {
        assertThat(howOut(game().ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED))), 1)).isEqualTo("b Tarun");
        assertThat(howOut(game().ball(Delivery.dot().out(Dismissal.striker(Type.LBW))), 1)).isEqualTo("lbw b Tarun");
        assertThat(howOut(game().ball(Delivery.dot().out(Dismissal.striker(Type.HIT_WICKET))), 1)).isEqualTo("hit wicket b Tarun");
        assertThat(howOut(game().ball(Delivery.dot().out(Dismissal.striker(Type.HIT_BALL_TWICE))), 1)).isEqualTo("hit the ball twice");
        assertThat(howOut(game().ball(Delivery.dot().out(Dismissal.of(Type.OBSTRUCTING_FIELD, new UUID(10, 2)))), 2)).isEqualTo("obstructing the field");
        var g = game();
        assertThat(howOut(g, 1)).isEqualTo("not out");
    }

    @Test
    void retirements_read_as_retirements() {
        var g = game();
        g.ok(new ScoringCommand.RetireBatter(g.b(1), ScoringCommand.RetirementType.HURT));
        assertThat(howOut(g, 1)).isEqualTo("retired hurt");
        g.batter(3);
        g.ok(new ScoringCommand.RetireBatter(g.b(2), ScoringCommand.RetirementType.OUT));
        assertThat(howOut(g, 2)).isEqualTo("retired out");
    }

    @Test
    void a_fielder_cannot_be_named_for_the_kinds_that_have_none() {
        var g = game();
        for (var type : new Type[]{Type.BOWLED, Type.LBW, Type.HIT_WICKET, Type.HIT_BALL_TWICE}) {
            g.rejBall(Delivery.dot().out(Dismissal.striker(type).by(g.f(3))), RuleViolation.FIELDER_NOT_ALLOWED);
        }
        g.rejBall(Delivery.dot().out(Dismissal.of(Type.OBSTRUCTING_FIELD, g.b(2)).by(g.f(3))), RuleViolation.FIELDER_NOT_ALLOWED);
    }

    @Test
    void the_fielder_must_be_in_the_fielding_xi_not_the_batting_side_or_a_stranger() {
        var g = game();
        g.rejBall(Delivery.dot().out(Dismissal.striker(Type.CAUGHT).by(g.b(3))), RuleViolation.FIELDER_NOT_IN_XI);
        g.rejBall(Delivery.dot().out(Dismissal.striker(Type.CAUGHT).by(new UUID(99, 99))), RuleViolation.FIELDER_NOT_IN_XI);
        g.rejBall(Delivery.dot().out(Dismissal.runOut(g.b(1), End.BATTING_END).by(g.b(2))), RuleViolation.FIELDER_NOT_IN_XI);
    }

    @Test
    void a_fielder_who_is_not_bowling_can_still_be_named_and_the_dismissal_replays_the_same() {
        var g = game().ball(Delivery.dot().out(Dismissal.striker(Type.CAUGHT).by(new UUID(20, 5))));

        assertThat(g.line(g.b(1)).fielderId()).isEqualTo(new UUID(20, 5));
        assertThat(ScoringEngine.replay(g.log)).isEqualTo(g.s);
    }
}
