package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.InningsState.Extras;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.RetirementType;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The hand-scored innings from the approved plan (docs/plans/cricket-2c-scoring-engine.md, section 10), asserted ball by ball and as a scorecard.
 * Two overs; a wide, a no-ball and a free hit, a bye, a run out after an odd run, a bowled, a retirement hurt and an injured bowler replaced mid-over.
 */
class GoldenInningsTest {

    private static final String[] LIONS = {"Arun", "Bala", "Chetan", "Dev", "Esha", "Farid", "Gita", "Hari", "Ishan", "Jai", "Kiran"};
    private static final String[] TIGERS = {"Tarun", "Uday", "Vik", "Wasim", "Yash"};

    private static Game named(Game g) {
        for (int i = 0; i < LIONS.length; i++) {
            g.names.put(g.b(i + 1), LIONS[i]);
        }
        for (int i = 0; i < TIGERS.length; i++) {
            g.names.put(g.f(i + 1), TIGERS[i]);
        }
        return g;
    }

    private static void at(Game g, String score, String striker, String nonStriker) {
        assertThat(g.score()).as("score").isEqualTo(score);
        assertThat(g.striker()).as("striker").isEqualTo(striker);
        assertThat(g.nonStriker()).as("non-striker").isEqualTo(nonStriker);
    }

    static Game lionsInnings() {
        var g = named(Game.standard(2));
        var ARUN = g.b(1);
        var BALA = g.b(2);

        // 1. Tarun to Arun: a wide, no runs run
        g.ball(Delivery.wide(0));
        at(g, "1/0", "Arun", "Bala");
        assertThat(g.s.legalBalls()).isZero();
        assertThat(g.line(ARUN).balls()).isZero();

        // 2. 0.1 Arun runs 1
        g.ball(Delivery.runs(1));
        at(g, "2/0", "Bala", "Arun");
        assertThat(g.s.oversText()).isEqualTo("0.1");

        // 3. Bala: a no-ball hit for a boundary four. Next ball is a free hit
        g.ball(Delivery.noBallBoundary(4));
        at(g, "7/0", "Bala", "Arun");
        assertThat(g.s.oversText()).isEqualTo("0.1");
        assertThat(g.s.freeHit()).isTrue();

        // 4. free hit: "bowled" is refused and nothing changes
        g.rejBall(Delivery.dot().out(Dismissal.striker(Type.BOWLED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
        at(g, "7/0", "Bala", "Arun");

        // 5. 0.2 free hit: Bala runs 2
        g.ball(Delivery.runs(2));
        at(g, "9/0", "Bala", "Arun");
        assertThat(g.s.freeHit()).isFalse();
        assertThat(g.s.oversText()).isEqualTo("0.2");

        // 6. 0.3 Bala misses, a bye
        g.ball(Delivery.bye(1));
        at(g, "10/0", "Arun", "Bala");

        // 7. 0.4 Arun runs 1, then is run out at the batting end going back for the second
        g.ball(Delivery.runs(1).out(Dismissal.runOut(ARUN, End.BATTING_END)));
        assertThat(g.score()).isEqualTo("11/1");
        assertThat(g.s.striker()).isNull();
        assertThat(g.nonStriker()).isEqualTo("Bala");
        assertThat(g.s.needsBatter()).isTrue();
        assertThat(g.s.oversText()).isEqualTo("0.4");
        g.batter(3);
        at(g, "11/1", "Chetan", "Bala");

        // 8. 0.5 Chetan is bowled
        g.ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED)));
        assertThat(g.score()).isEqualTo("11/2");
        g.batter(4);
        at(g, "11/2", "Dev", "Bala");

        // 9. 0.6 Dev, a dot ball. End of the over: the batters swap and Tarun cannot bowl the next one
        g.ball(Delivery.dot());
        at(g, "11/2", "Bala", "Dev");
        assertThat(g.s.needsBowler()).isTrue();
        assertThat(g.s.oversText()).isEqualTo("1.0");
        g.rej(new SelectBowler(g.f(1)), RuleViolation.BOWLER_BOWLED_PREVIOUS_OVER);
        g.bowler(2);

        // 10. 1.1 Uday to Bala: 1 run
        g.ball(Delivery.runs(1));
        at(g, "12/2", "Dev", "Bala");

        // 11. 1.2 Dev runs 2
        g.ball(Delivery.runs(2));
        at(g, "14/2", "Dev", "Bala");

        // 12. Dev retires hurt: not a wicket. Esha comes in on strike
        g.ok(new RetireBatter(g.b(4), RetirementType.HURT));
        assertThat(g.score()).isEqualTo("14/2");
        assertThat(g.s.needsBatter()).isTrue();
        g.batter(5);
        at(g, "14/2", "Esha", "Bala");

        // 13. Uday is injured. Vik bowls the rest of the over
        g.changeBowler(3);
        assertThat(g.s.currentBowler()).isEqualTo(g.f(3));

        // 14. 1.3 Esha, dot
        g.ball(Delivery.dot());
        at(g, "14/2", "Esha", "Bala");

        // 15. 1.4 Esha runs 1
        g.ball(Delivery.runs(1));
        at(g, "15/2", "Bala", "Esha");

        // 16. 1.5 Bala hits a six
        g.ball(Delivery.boundary(6));
        at(g, "21/2", "Bala", "Esha");

        // 17. 1.6 Bala runs 1: the single swaps them, the end of the over swaps them back. Innings complete
        g.ball(Delivery.runs(1));
        at(g, "22/2", "Bala", "Esha");
        assertThat(g.s.endReason()).isEqualTo(EndReason.OVERS_COMPLETE);
        assertThat(g.s.needsBowler()).isFalse();
        return g;
    }

    @Test
    void the_hand_scored_innings_ball_by_ball() {
        var g = lionsInnings();

        assertThat(g.s.oversText()).isEqualTo("2.0");
        assertThat(ScoringEngine.replay(g.log)).as("replaying the log gives the same innings").isEqualTo(g.s);
    }

    @Test
    void the_hand_scored_innings_as_a_scorecard() {
        var g = lionsInnings();
        var card = Scorecard.of(g.s);

        assertThat(card.runs()).isEqualTo(22);
        assertThat(card.wickets()).isEqualTo(2);
        assertThat(card.overs()).isEqualTo("2.0");
        assertThat(card.extras()).isEqualTo(new Extras(1, 1, 1, 0));
        assertThat(card.extras().total()).isEqualTo(3);

        assertThat(card.batters()).extracting(b -> g.name(b.line().playerId()), b -> b.line().runs(), b -> b.line().balls(), b -> b.line().fours(), b -> b.line().sixes(),
                b -> b.line().status(), b -> b.line().dismissal(), b -> g.name(b.line().bowlerId()), BatterCardNotOut::of)
            .containsExactly(
                tuple("Arun", 2, 2, 0, 0, BatterStatus.OUT, Type.RUN_OUT, null, false),
                tuple("Bala", 14, 6, 1, 1, BatterStatus.NOT_OUT, null, null, true),
                tuple("Chetan", 0, 1, 0, 0, BatterStatus.OUT, Type.BOWLED, "Tarun", false),
                tuple("Dev", 2, 2, 0, 0, BatterStatus.RETIRED_HURT, null, null, true),
                tuple("Esha", 1, 2, 0, 0, BatterStatus.NOT_OUT, null, null, true));
        assertThat(card.yetToBat()).extracting(g::name).containsExactly("Farid", "Gita", "Hari", "Ishan", "Jai", "Kiran");

        assertThat(card.fallOfWickets()).extracting(f -> f.fall().number(), f -> f.fall().runs(), f -> g.name(f.fall().batterId()), f -> f.fall().type(), f -> f.overs())
            .containsExactly(tuple(1, 11, "Arun", Type.RUN_OUT, "0.4"), tuple(2, 11, "Chetan", Type.BOWLED, "0.5"));

        assertThat(card.bowlers()).extracting(b -> g.name(b.line().playerId()), b -> b.overs(), b -> b.line().creditedOvers(), b -> b.line().maidens(), b -> b.line().runs(),
                b -> b.line().wickets(), b -> b.line().wides(), b -> b.line().noBalls(), b -> b.economy().toPlainString())
            .containsExactly(
                tuple("Tarun", "1.0", 1, 0, 10, 1, 1, 1, "10.00"),
                tuple("Uday", "0.2", 0, 0, 3, 0, 0, 0, "9.00"),
                tuple("Vik", "0.4", 1, 0, 8, 0, 0, 0, "12.00"));

        int batRuns = card.batters().stream().mapToInt(b -> b.line().runs()).sum();
        assertThat(batRuns).isEqualTo(19);
        assertThat(batRuns + card.extras().total()).isEqualTo(card.runs());
        int bowlerRuns = card.bowlers().stream().mapToInt(b -> b.line().runs()).sum();
        assertThat(bowlerRuns + card.extras().byes() + card.extras().legByes()).isEqualTo(card.runs());
        assertThat(card.bowlers().stream().mapToInt(b -> b.line().legalBalls()).sum()).isEqualTo(12);
    }

    /** Whether a batter is shown as not out (at the crease, or retired hurt). */
    private static final class BatterCardNotOut {
        static boolean of(Scorecard.BatterCard card) {
            return card.notOut();
        }
    }

    @Test
    void a_chase_of_the_hand_scored_innings_is_won_off_the_last_ball() {
        var first = lionsInnings();
        var g = named(new Game(com.dsports.cricket.domain.model.MatchRules.standard(), 2, 11, 5, first.s.runs() + 1, Game.TIGERS, Game.LIONS));
        assertThat(g.s.target()).isEqualTo(23);

        g.ball(Delivery.boundary(6)).ball(Delivery.boundary(4)).ball(Delivery.runs(1)).ball(Delivery.runs(1)).ball(Delivery.boundary(6)).ball(Delivery.dot());
        assertThat(g.score()).isEqualTo("18/0");
        assertThat(g.s.needsBowler()).isTrue();
        g.bowler(2);
        g.ball(Delivery.wide(0)).ball(Delivery.runs(1)).dots(4);
        assertThat(g.score()).isEqualTo("20/0");
        assertThat(g.s.isComplete()).isFalse();
        g.ball(Delivery.runs(3));

        assertThat(g.score()).isEqualTo("23/0");
        assertThat(g.s.endReason()).as("winning on the last ball is a win, not just the overs running out").isEqualTo(EndReason.TARGET_REACHED);
        var result = MatchResultCalculator.compute(first.s, g.s);
        assertThat(result.type()).isEqualTo(MatchResultCalculator.Type.WON_BY_WICKETS);
        assertThat(result.winnerTeamId()).isEqualTo(Game.TIGERS);
        assertThat(result.margin()).isEqualTo(10);
        assertThat(result.ballsRemaining()).isZero();
        assertThat(ScoringEngine.replay(g.log)).isEqualTo(g.s);
    }
}
