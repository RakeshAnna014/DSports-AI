package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Dismissal.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The approved dismissal table, written out independently of the implementation: 8 types x 3 kinds of ball x free hit on and off. */
class DismissalRulesTest {

    private static final Set<Type> NORMAL = EnumSet.allOf(Type.class);
    private static final Set<Type> WIDE = EnumSet.of(Type.STUMPED, Type.RUN_OUT, Type.HIT_WICKET, Type.OBSTRUCTING_FIELD);
    private static final Set<Type> NO_BALL_OR_FREE_HIT = EnumSet.of(Type.RUN_OUT, Type.OBSTRUCTING_FIELD, Type.HIT_BALL_TWICE);

    private static boolean expected(Kind kind, boolean freeHit, Type type) {
        var byKind = switch (kind) {
            case NORMAL -> NORMAL;
            case WIDE -> WIDE;
            case NO_BALL -> NO_BALL_OR_FREE_HIT;
        };
        return byKind.contains(type) && (!freeHit || NO_BALL_OR_FREE_HIT.contains(type));
    }

    @Test
    void all_forty_eight_combinations_match_the_table() {
        int checked = 0;
        for (var kind : Kind.values()) {
            for (boolean freeHit : new boolean[]{false, true}) {
                for (var type : Type.values()) {
                    assertThat(DismissalRules.isAllowed(kind, freeHit, type))
                        .as("%s on a %s%s", type, kind, freeHit ? " (free hit)" : "")
                        .isEqualTo(expected(kind, freeHit, type));
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(48);
    }

    @Test
    void handled_the_ball_is_not_a_separate_type() {
        assertThat(Type.values()).extracting(Enum::name).doesNotContain("HANDLED_THE_BALL", "TIMED_OUT");
    }

    @ParameterizedTest
    @EnumSource(Type.class)
    void a_wide_refuses_what_the_wide_table_refuses_and_says_it_is_the_wide(Type type) {
        var violation = DismissalRules.violation(Kind.WIDE, false, type);
        assertThat(violation.isPresent()).isEqualTo(!WIDE.contains(type));
        violation.ifPresent(v -> assertThat(v).isEqualTo(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_WIDE));
    }

    @Test
    void the_kind_of_ball_is_named_before_the_free_hit() {
        assertThat(DismissalRules.violation(Kind.NORMAL, true, Type.BOWLED)).contains(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
        assertThat(DismissalRules.violation(Kind.NO_BALL, true, Type.BOWLED)).contains(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_NO_BALL);
        assertThat(DismissalRules.violation(Kind.WIDE, true, Type.CAUGHT)).contains(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_WIDE);
        assertThat(DismissalRules.violation(Kind.WIDE, true, Type.STUMPED)).contains(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
    }

    @Test
    void on_a_free_hit_wide_only_run_out_and_obstructing_remain() {
        assertThat(EnumSet.allOf(Type.class).stream().filter(t -> DismissalRules.isAllowed(Kind.WIDE, true, t)))
            .containsExactlyInAnyOrder(Type.RUN_OUT, Type.OBSTRUCTING_FIELD);
    }

    @Test
    void only_five_kinds_are_credited_to_the_bowler() {
        assertThat(EnumSet.allOf(Type.class).stream().filter(DismissalRules::creditedToBowler))
            .containsExactlyInAnyOrder(Type.BOWLED, Type.CAUGHT, Type.LBW, Type.STUMPED, Type.HIT_WICKET);
    }

    @Test
    void only_run_out_and_obstructing_can_dismiss_the_non_striker() {
        assertThat(EnumSet.allOf(Type.class).stream().filter(DismissalRules::eitherBatterCanBeOut)).containsExactlyInAnyOrder(Type.RUN_OUT, Type.OBSTRUCTING_FIELD);
    }

    @Test
    void only_run_out_obstructing_and_double_hit_keep_the_runs_completed() {
        assertThat(EnumSet.allOf(Type.class).stream().filter(DismissalRules::keepsCompletedRuns))
            .containsExactlyInAnyOrder(Type.RUN_OUT, Type.OBSTRUCTING_FIELD, Type.HIT_BALL_TWICE);
    }
}
