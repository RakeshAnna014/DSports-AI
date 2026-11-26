package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Dismissal.Type;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/** Which dismissals are possible on which ball. This is the one place the table lives (see the plan, section 3). */
public final class DismissalRules {

    private static final Set<Type> ON_NORMAL_BALL = EnumSet.allOf(Type.class);
    private static final Set<Type> ON_WIDE = EnumSet.of(Type.STUMPED, Type.RUN_OUT, Type.HIT_WICKET, Type.OBSTRUCTING_FIELD);
    private static final Set<Type> ON_NO_BALL = EnumSet.of(Type.RUN_OUT, Type.OBSTRUCTING_FIELD, Type.HIT_BALL_TWICE);
    private static final Set<Type> ON_FREE_HIT = ON_NO_BALL;
    private static final Set<Type> CREDITED_TO_BOWLER = EnumSet.of(Type.BOWLED, Type.CAUGHT, Type.LBW, Type.STUMPED, Type.HIT_WICKET);
    private static final Set<Type> EITHER_BATTER = EnumSet.of(Type.RUN_OUT, Type.OBSTRUCTING_FIELD);
    private static final Set<Type> KEEPS_COMPLETED_RUNS = EnumSet.of(Type.RUN_OUT, Type.OBSTRUCTING_FIELD, Type.HIT_BALL_TWICE);

    private DismissalRules() {
    }

    /** Why this dismissal cannot happen on this ball, or empty when it can. The kind of ball is checked first, then the free hit. */
    public static Optional<RuleViolation> violation(Kind kind, boolean freeHit, Type type) {
        var byKind = switch (kind) {
            case NORMAL -> ON_NORMAL_BALL;
            case WIDE -> ON_WIDE;
            case NO_BALL -> ON_NO_BALL;
        };
        if (!byKind.contains(type)) {
            return Optional.of(kind == Kind.WIDE ? RuleViolation.DISMISSAL_NOT_ALLOWED_ON_WIDE : RuleViolation.DISMISSAL_NOT_ALLOWED_ON_NO_BALL);
        }
        if (freeHit && !ON_FREE_HIT.contains(type)) {
            return Optional.of(RuleViolation.DISMISSAL_NOT_ALLOWED_ON_FREE_HIT);
        }
        return Optional.empty();
    }

    public static boolean isAllowed(Kind kind, boolean freeHit, Type type) {
        return violation(kind, freeHit, type).isEmpty();
    }

    /** Whether the wicket is credited to the bowler. A run out, an obstruction and a double hit are not. */
    public static boolean creditedToBowler(Type type) {
        return CREDITED_TO_BOWLER.contains(type);
    }

    /** Only a run out or an obstruction can dismiss the non-striker. */
    public static boolean eitherBatterCanBeOut(Type type) {
        return EITHER_BATTER.contains(type);
    }

    /** Only a catch, a stumping and a run out can name the fielder involved. */
    public static boolean canNameFielder(Type type) {
        return type == Type.CAUGHT || type == Type.STUMPED || type == Type.RUN_OUT;
    }

    /** False for bowled, caught, lbw, stumped and hit wicket: no run is scored, apart from a wide's penalty. */
    public static boolean keepsCompletedRuns(Type type) {
        return KEEPS_COMPLETED_RUNS.contains(type);
    }
}
