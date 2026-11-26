package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery.Kind;
import com.dsports.cricket.domain.rules.Delivery.RunsType;
import com.dsports.cricket.domain.rules.InningsState.EndReason;
import com.dsports.cricket.domain.rules.ScoringCommand.ChangeBowlerMidOver;
import com.dsports.cricket.domain.rules.ScoringCommand.Deliver;
import com.dsports.cricket.domain.rules.ScoringCommand.EndInnings;
import com.dsports.cricket.domain.rules.ScoringCommand.RetireBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.RetirementType;
import com.dsports.cricket.domain.rules.ScoringCommand.ReviseTarget;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBatter;
import com.dsports.cricket.domain.rules.ScoringCommand.SelectBowler;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** A seeded random scorer that mixes legal and illegal commands. Used by the property tests of the engine and of the JSON codec. */
public final class RandomScorer {

    private RandomScorer() {
    }

    static Game newGame(Random r) {
        int players = 2 + r.nextInt(10);
        int overs = 1 + r.nextInt(5);
        var rules = new MatchRules(4 + r.nextInt(5), players, r.nextInt(3) == 0 ? 1 : null, r.nextInt(3), r.nextInt(3), r.nextInt(5) != 0, r.nextInt(4) == 0);
        Integer target = r.nextInt(3) == 0 ? 1 + r.nextInt(70) : null;
        return new Game(rules, overs, players, Math.min(players, 2 + r.nextInt(4)), target);
    }

    public static ScoringCommand nextCommand(Random r, Game g) {
        return nextCommand(r, g.s);
    }

    /** The next command for an innings in this state (used by the match-level vector generator, which has no {@link Game}). */
    public static ScoringCommand nextCommand(Random r, InningsState s) {
        if (s.needsBatter() && r.nextInt(10) < 8) {
            return new SelectBatter(pick(r, s.battingXi()));
        }
        if (s.needsBowler() && r.nextInt(10) < 8) {
            return new SelectBowler(pick(r, s.bowlingXi()));
        }
        int p = r.nextInt(1000);
        if (p < 800) {
            return new Deliver(randomDelivery(r, s));
        }
        if (p < 850) {
            return new RetireBatter(r.nextInt(5) == 0 ? pick(r, s.battingXi()) : crease(r, s), r.nextInt(8) == 0 ? null : RetirementType.values()[r.nextInt(2)]);
        }
        if (p < 920) {
            return new ChangeBowlerMidOver(pick(r, s.bowlingXi()));
        }
        if (p < 925) {
            return new EndInnings(EndReason.values()[r.nextInt(EndReason.values().length)]);
        }
        if (p < 940) {
            return new ReviseTarget(r.nextInt(80));
        }
        return r.nextBoolean() ? new SelectBatter(pick(r, s.battingXi())) : new SelectBowler(pick(r, s.bowlingXi()));
    }

    private static Delivery randomDelivery(Random r, InningsState s) {
        int k = r.nextInt(100);
        var kind = k < 76 ? Kind.NORMAL : k < 88 ? Kind.WIDE : Kind.NO_BALL;
        int runs = r.nextInt(100) < 60 ? r.nextInt(3) : r.nextInt(9);
        var type = RunsType.values()[r.nextInt(RunsType.values().length)];
        if (r.nextInt(100) < 60) {
            type = RunsType.BAT;
        }
        boolean boundary = r.nextInt(10) == 0;
        if (boundary) {
            runs = r.nextBoolean() ? 4 : 6;
        }
        Dismissal dismissal = null;
        if (r.nextInt(100) < 12) {
            var type2 = Dismissal.Type.values()[r.nextInt(Dismissal.Type.values().length)];
            UUID batter = r.nextInt(3) == 0 ? null : (r.nextInt(8) == 0 ? pick(r, s.battingXi()) : crease(r, s));
            var end = r.nextInt(6) == 0 ? null : Dismissal.End.values()[r.nextInt(2)];
            dismissal = new Dismissal(type2, batter, end);
        }
        return new Delivery(kind, runs, type, boundary, dismissal);
    }

    private static UUID crease(Random r, InningsState s) {
        var at = new ArrayList<UUID>();
        if (s.atBattingEnd() != null) {
            at.add(s.atBattingEnd());
        }
        if (s.atBowlersEnd() != null) {
            at.add(s.atBowlersEnd());
        }
        return at.isEmpty() ? pick(r, s.battingXi()) : pick(r, at);
    }

    private static UUID pick(Random r, List<UUID> from) {
        return from.get(r.nextInt(from.size()));
    }

}
