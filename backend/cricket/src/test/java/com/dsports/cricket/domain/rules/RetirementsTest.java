package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.ScoringCommand.EndInnings;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.RetirementType;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetirementsTest {

    private static Game retireHurt(Game g, int batter) {
        return g.ok(new RetireBatter(g.b(batter), RetirementType.HURT));
    }

    @Test
    void a_batter_retired_hurt_is_not_out_and_no_wicket_falls() {
        var g = Game.standard(20).ball(Delivery.runs(2));
        retireHurt(g, 1);

        assertThat(g.s.wickets()).isZero();
        assertThat(g.s.fallOfWickets()).isEmpty();
        assertThat(g.line(g.b(1)).status()).isEqualTo(BatterStatus.RETIRED_HURT);
        assertThat(g.line(g.b(1)).runs()).isEqualTo(2);
        assertThat(g.s.needsBatter()).isTrue();
        assertThat(g.s.striker()).isNull();
        g.rejBall(Delivery.dot(), RuleViolation.NEEDS_NEW_BATTER);
        g.batter(3);
        assertThat(g.striker()).isEqualTo("bat3");
        assertThat(g.nonStriker()).isEqualTo("bat2");
    }

    @Test
    void a_non_striker_retiring_leaves_the_striker_on_strike_and_the_new_batter_at_the_other_end() {
        var g = Game.standard(20);
        retireHurt(g, 2);
        g.batter(3);

        assertThat(g.striker()).isEqualTo("bat1");
        assertThat(g.nonStriker()).isEqualTo("bat3");
    }

    @Test
    void a_batter_who_retired_hurt_can_come_back_with_his_score_when_a_batter_is_next_needed() {
        var g = Game.standard(20).ball(Delivery.runs(2));
        retireHurt(g, 1);
        g.batter(3).ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)));
        g.batter(1);

        assertThat(g.s.batters()).hasSize(3);
        assertThat(g.line(g.b(1)).status()).isEqualTo(BatterStatus.NOT_OUT);
        assertThat(g.line(g.b(1)).runs()).isEqualTo(2);
        assertThat(g.line(g.b(1)).balls()).isEqualTo(1);
        assertThat(g.striker()).isEqualTo("bat1");
        assertThat(g.s.yetToBat()).hasSize(8);
    }

    @Test
    void a_batter_retired_hurt_may_come_back_straight_away_at_the_next_batter_slot() {
        var g = Game.standard(20);
        retireHurt(g, 1);
        g.batter(1);

        assertThat(g.striker()).isEqualTo("bat1");
        assertThat(g.line(g.b(1)).status()).isEqualTo(BatterStatus.NOT_OUT);
    }

    @Test
    void a_batter_retired_out_counts_as_a_wicket_with_no_bowler_credit_and_cannot_return() {
        var g = Game.standard(20).ball(Delivery.runs(1)).ball(Delivery.runs(1));
        g.ok(new RetireBatter(g.b(1), RetirementType.OUT));

        assertThat(g.s.wickets()).isEqualTo(1);
        assertThat(g.line(g.b(1)).status()).isEqualTo(BatterStatus.RETIRED_OUT);
        assertThat(g.line(g.b(1)).dismissal()).isNull();
        assertThat(g.line(g.b(1)).bowlerId()).isNull();
        assertThat(g.bowlerLine(1).wickets()).isZero();
        assertThat(g.s.fallOfWickets()).containsExactly(new InningsState.FallOfWicket(1, 2, g.b(1), null, 2));
        g.rej(new SelectBatter(g.b(1)), RuleViolation.BATTER_ALREADY_OUT);
        g.batter(3);
        assertThat(g.s.needsBatter()).isFalse();
    }

    @Test
    void retiring_out_can_end_the_innings_when_nobody_is_left() {
        var g = new Game(Game.withPlayers(2), 20, 2, 2, null);
        g.ok(new RetireBatter(g.b(1), RetirementType.OUT));

        assertThat(g.s.endReason()).isEqualTo(EndReason.ALL_OUT);
    }

    @Test
    void a_retirement_is_only_accepted_for_a_batter_at_the_crease_with_a_type() {
        var g = Game.standard(20);
        g.rej(new RetireBatter(g.b(5), RetirementType.HURT), RuleViolation.BATTER_NOT_AT_CREASE);
        g.rej(new RetireBatter(null, RetirementType.HURT), RuleViolation.BATTER_NOT_AT_CREASE);
        g.rej(new RetireBatter(g.b(1), null), RuleViolation.INVALID_RETIREMENT);
    }

    @Test
    void a_second_retirement_waits_until_the_first_batter_has_been_replaced() {
        var g = Game.standard(20);
        retireHurt(g, 1);
        g.rej(new RetireBatter(g.b(2), RetirementType.HURT), RuleViolation.NEEDS_NEW_BATTER);
    }

    @Test
    void a_batter_can_retire_between_overs_before_the_next_bowler_is_named() {
        var g = Game.standard(20).dots(6);
        assertThat(g.s.needsBowler()).isTrue();
        retireHurt(g, 2);
        g.batter(3).bowler(2);

        assertThat(g.s.needsBowler()).isFalse();
        assertThat(g.striker()).isEqualTo("bat3");
    }

    @Test
    void when_only_a_retired_batter_is_left_to_come_in_he_must_return_or_the_scorer_ends_the_innings() {
        var g = new Game(Game.withPlayers(3), 20, 3, 2, null);
        retireHurt(g, 1);
        g.batter(3);
        retireHurt(g, 2);

        assertThat(g.s.isComplete()).isFalse();
        assertThat(g.s.needsBatter()).isTrue();
        assertThat(g.s.yetToBat()).isEmpty();
        g.rej(new EndInnings(EndReason.ALL_OUT), RuleViolation.CANNOT_END_YET);
        g.batter(2);
        assertThat(g.s.needsBatter()).isFalse();
    }

    @Test
    void the_scorer_can_end_the_innings_with_no_batters_left_only_when_everybody_has_batted() {
        var g = new Game(Game.withPlayers(3), 20, 3, 2, null);
        retireHurt(g, 1);
        g.rej(new EndInnings(EndReason.NO_BATTERS_LEFT), RuleViolation.CANNOT_END_YET);
        g.batter(3);
        retireHurt(g, 2);
        g.ok(new EndInnings(EndReason.NO_BATTERS_LEFT));

        assertThat(g.s.endReason()).isEqualTo(EndReason.NO_BATTERS_LEFT);
        assertThat(g.s.needsBatter()).isFalse();
        assertThat(g.s.wickets()).isZero();
    }

    @Test
    void a_scorecard_shows_a_retired_hurt_batter_as_not_out() {
        var g = Game.standard(20).ball(Delivery.runs(2));
        retireHurt(g, 1);
        var card = Scorecard.of(g.s);

        assertThat(card.batters().get(0).notOut()).isTrue();
        assertThat(card.batters().get(0).line().status()).isEqualTo(BatterStatus.RETIRED_HURT);
        assertThat(card.wickets()).isZero();
    }

    @Test
    void a_retirement_does_not_use_the_free_hit() {
        var g = Game.standard(20).ball(Delivery.noBall(0));
        retireHurt(g, 2);
        g.batter(3);

        assertThat(g.s.freeHit()).isTrue();
        g.rejBall(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
    }

    @Test
    void retirements_are_refused_once_the_innings_is_over() {
        var g = new Game(Game.withPlayers(2), 20, 2, 2, null);
        g.ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED)));
        g.rej(new RetireBatter(g.b(2), RetirementType.HURT), RuleViolation.INNINGS_COMPLETE);
        g.rej(new SelectBatter(new UUID(10, 1)), RuleViolation.INNINGS_COMPLETE);
    }
}
