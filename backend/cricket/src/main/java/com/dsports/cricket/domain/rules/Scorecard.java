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
