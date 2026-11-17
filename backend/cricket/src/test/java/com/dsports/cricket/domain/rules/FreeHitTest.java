package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Dismissal.End;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FreeHitTest {

    @Test
    void a_no_ball_makes_the_next_delivery_a_free_hit_and_a_legal_ball_uses_it_up() {
        var g = Game.standard(20).ball(Delivery.noBall(0));
        assertThat(g.s.freeHit()).isTrue();

        g.ball(Delivery.runs(1));
        assertThat(g.s.freeHit()).isFalse();
        g.ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED)));
        assertThat(g.s.wickets()).isEqualTo(1);
    }

    @Test
    void on_a_free_hit_the_batter_cannot_be_bowled_caught_lbw_stumped_or_hit_wicket() {
        for (var type : new Type[]{Type.BOWLED, Type.CAUGHT, Type.LBW, Type.STUMPED, Type.HIT_WICKET}) {
            Game.standard(20).ball(Delivery.noBall(0)).rejBall(Delivery.dot().out(Dismissal.striker(type)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
        }
    }

    @Test
    void on_a_free_hit_a_run_out_obstructing_and_a_double_hit_are_still_out() {
        var g = Game.standard(20);
        g.ball(Delivery.noBall(0)).ball(Delivery.runs(1).out(Dismissal.runOut(new UUID(10, 1), End.BOWLERS_END)));
        assertThat(g.s.wickets()).isEqualTo(1);
        assertThat(g.s.freeHit()).isFalse();

        Game.standard(20).ball(Delivery.noBall(0)).ball(Delivery.dot().out(Dismissal.striker(Type.HIT_BALL_TWICE)));
        Game.standard(20).ball(Delivery.noBall(0)).ball(Delivery.dot().out(Dismissal.of(Type.OBSTRUCTING_FIELD, new UUID(10, 2))));
    }

    @Test
    void a_wide_does_not_use_up_the_free_hit() {
        var g = Game.standard(20).ball(Delivery.noBall(0)).ball(Delivery.wide(0));

        assertThat(g.s.freeHit()).isTrue();
        g.rejBall(Delivery.dot().out(Dismissal.striker(Type.LBW)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
        g.ball(Delivery.dot());
        assertThat(g.s.freeHit()).isFalse();
    }

    @Test
    void a_stumping_off_a_wide_on_a_free_hit_is_not_allowed() {
        Game.standard(20).ball(Delivery.noBall(0))
            .rejBall(Delivery.wide(0).out(Dismissal.striker(Type.STUMPED)), RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
    }

    @Test
    void a_no_ball_on_a_free_hit_gives_another_free_hit() {
        var g = Game.standard(20).ball(Delivery.noBall(0)).ball(Delivery.noBall(1));

        assertThat(g.s.freeHit()).isTrue();
        g.ball(Delivery.dot());
        assertThat(g.s.freeHit()).isFalse();
    }

    @Test
    void an_ordinary_delivery_after_a_free_hit_is_not_a_free_hit() {
        var g = Game.standard(20).ball(Delivery.noBall(0)).ball(Delivery.dot());

        g.ball(Delivery.dot().out(Dismissal.striker(Type.CAUGHT)));
        assertThat(g.s.wickets()).isEqualTo(1);
    }

    @Test
    void the_free_hit_can_be_switched_off_for_the_match() {
        var g = new Game(Game.rules(6, 11, null, 1, 1, false, false), 20, 11, 5, null).ball(Delivery.noBall(0));

        assertThat(g.s.freeHit()).isFalse();
        g.ball(Delivery.dot().out(Dismissal.striker(Type.BOWLED)));
        assertThat(g.s.wickets()).isEqualTo(1);
    }

    @Test
    void the_free_hit_carries_into_the_last_ball_of_the_over_and_is_used_there() {
        var g = Game.standard(20).dots(5).ball(Delivery.noBall(0));

        assertThat(g.s.over().legalBalls()).isEqualTo(5);
        g.ball(Delivery.dot());
        assertThat(g.s.freeHit()).isFalse();
        assertThat(g.s.needsBowler()).isTrue();
    }
}
