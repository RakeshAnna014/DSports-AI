package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.rules.InningsState.BatterLine;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.BowlerLine;
import com.dsports.cricket.domain.rules.InningsState.Extras;
import com.dsports.cricket.domain.rules.InningsState.FallOfWicket;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A scorecard computed from an innings state (ids only; names are added where it is shown). Batters are in the order they came in. */
public record Scorecard(int runs, int wickets, String overs, Extras extras, List<BatterCard> batters, List<UUID> yetToBat, List<BowlerCard> bowlers,
                        List<FallCard> fallOfWickets, List<InningsState.Waiver> waivers, UUID striker, UUID nonStriker, UUID currentBowler, Integer target) {

    /** {@code notOut} is true for a batter at the crease and for one who retired hurt (who may still return). */
    public record BatterCard(BatterLine line, boolean notOut) {
    }

    public record BowlerCard(BowlerLine line, String overs, BigDecimal economy) {
    }

    public record FallCard(FallOfWicket fall, String overs) {
    }

    /**
     * How a batter was dismissed, as printed on a scorecard: "b Tarun", "c Ravi b Tarun", "c & b Tarun", "st Ravi b Tarun", "caught b Tarun" (no fielder named), "run out (Ravi)", "run out",
     * "lbw b Tarun", "hit wicket b Tarun", "hit the ball twice", "obstructing the field", "retired hurt", "retired out", or "not out".
     */
    public static String howOut(BatterLine line, java.util.function.Function<UUID, String> nameOf) {
        return switch (line.status()) {
            case NOT_OUT -> "not out";
            case RETIRED_HURT -> "retired hurt";
            case RETIRED_OUT -> "retired out";
            case OUT -> {
                var bowler = line.bowlerId() == null ? null : nameOf.apply(line.bowlerId());
                var fielder = line.fielderId() == null ? null : nameOf.apply(line.fielderId());
                yield switch (line.dismissal()) {
                    case BOWLED -> "b " + bowler;
                    case CAUGHT -> line.fielderId() == null ? "caught b " + bowler : line.fielderId().equals(line.bowlerId()) ? "c & b " + bowler : "c " + fielder + " b " + bowler;
                    case STUMPED -> line.fielderId() == null ? "stumped b " + bowler : "st " + fielder + " b " + bowler;
                    case LBW -> "lbw b " + bowler;
                    case HIT_WICKET -> "hit wicket b " + bowler;
                    case RUN_OUT -> fielder == null ? "run out" : "run out (" + fielder + ")";
                    case HIT_BALL_TWICE -> "hit the ball twice";
                    case OBSTRUCTING_FIELD -> "obstructing the field";
                };
            }
        };
    }

    public static Scorecard of(InningsState s) {
        int bpo = s.rules().ballsPerOver();
        return new Scorecard(s.runs(), s.wickets(), s.oversText(), s.extras(),
            s.batters().stream().map(b -> new BatterCard(b, b.status() == BatterStatus.NOT_OUT || b.status() == BatterStatus.RETIRED_HURT)).toList(),
            s.yetToBat(),
            s.bowlers().stream().map(b -> new BowlerCard(b, InningsState.oversText(b.legalBalls(), bpo), b.economy(bpo))).toList(),
            s.fallOfWickets().stream().map(f -> new FallCard(f, InningsState.oversText(f.legalBalls(), bpo))).toList(),
            s.waivers(), s.striker(), s.nonStriker(), s.currentBowler(), s.target());
    }
}
