package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything known about one innings. Immutable: the engine returns a new state for every accepted command.
 *
 * <p>The two creases are {@code atBattingEnd} (where the bowler bowls to; that batter is the striker) and {@code atBowlersEnd}. An end is null while a
 * wicket or retirement has left it empty and a new batter has to be named, and also for the lone batter of a last-man-stands innings.
 */
public record InningsState(Status status, EndReason endReason, int overs, MatchRules rules, UUID battingTeamId, UUID bowlingTeamId,
                           List<UUID> battingXi, List<UUID> bowlingXi, Integer target,
                           int runs, int wickets, int legalBalls, Extras extras,
                           UUID atBattingEnd, UUID atBowlersEnd, List<BatterLine> batters, List<BowlerLine> bowlers,
                           UUID currentBowler, UUID previousOverBowler, Over over, boolean freeHit, boolean needsBatter, boolean needsBowler,
                           List<FallOfWicket> fallOfWickets, List<Waiver> waivers) {

    public enum Status { NOT_STARTED, IN_PROGRESS, COMPLETE }

    public enum EndReason { ALL_OUT, OVERS_COMPLETE, TARGET_REACHED, DECLARED, NO_BATTERS_LEFT }

    public enum BatterStatus { NOT_OUT, OUT, RETIRED_HURT, RETIRED_OUT }

    public record Extras(int wides, int noBalls, int byes, int legByes) {
        public static final Extras NONE = new Extras(0, 0, 0, 0);

        public int total() {
            return wides + noBalls + byes + legByes;
        }
    }

    /** {@code dismissal} is null while not out and for a retirement; {@code bowlerId} is set only when the wicket is credited to a bowler. */
    public record BatterLine(UUID playerId, int runs, int balls, int fours, int sixes, BatterStatus status, Dismissal.Type dismissal, UUID bowlerId, UUID fielderId) {
        static BatterLine arrives(UUID playerId) {
            return new BatterLine(playerId, 0, 0, 0, 0, BatterStatus.NOT_OUT, null, null, null);
        }

        BatterLine scored(int addRuns, boolean faced, boolean four, boolean six) {
            return new BatterLine(playerId, runs + addRuns, balls + (faced ? 1 : 0), fours + (four ? 1 : 0), sixes + (six ? 1 : 0), status, dismissal, bowlerId, fielderId);
        }

        BatterLine with(BatterStatus newStatus, Dismissal.Type newDismissal, UUID newBowlerId, UUID newFielderId) {
            return new BatterLine(playerId, runs, balls, fours, sixes, newStatus, newDismissal, newBowlerId, newFielderId);
        }
    }

    /** {@code legalBalls} are the balls actually bowled; {@code creditedOvers} are the overs that count towards the limit (see the mid-over rule). */
    public record BowlerLine(UUID playerId, int legalBalls, int creditedOvers, int maidens, int runs, int wickets, int wides, int noBalls) {
        static BowlerLine arrives(UUID playerId) {
            return new BowlerLine(playerId, 0, 0, 0, 0, 0, 0, 0);
        }

        BowlerLine delivered(int legal, int conceded, int wide, int noBall) {
            return new BowlerLine(playerId, legalBalls + legal, creditedOvers, maidens, runs + conceded, wickets, wides + wide, noBalls + noBall);
        }

        BowlerLine creditedOver(boolean maiden) {
            return new BowlerLine(playerId, legalBalls, creditedOvers + 1, maidens + (maiden ? 1 : 0), runs, wickets, wides, noBalls);
        }

        BowlerLine tookWicket() {
            return new BowlerLine(playerId, legalBalls, creditedOvers, maidens, runs, wickets + 1, wides, noBalls);
        }

        /** Runs per over: runs divided by overs bowled (balls actually bowled). Zero before the first legal ball. */
        public java.math.BigDecimal economy(int ballsPerOver) {
            if (legalBalls == 0) {
                return java.math.BigDecimal.ZERO.setScale(2);
            }
            return java.math.BigDecimal.valueOf((long) runs * ballsPerOver).divide(java.math.BigDecimal.valueOf(legalBalls), 2, java.math.RoundingMode.HALF_UP);
        }
    }

    /** {@code type} is null for a batter who retired out. */
    public record FallOfWicket(int number, int runs, UUID batterId, Dismissal.Type type, int legalBalls) {
    }

    /** A rule that was set aside as a last resort so the innings could go on. */
    public enum WaiverKind { OVER_LIMIT, CONSECUTIVE_OVER }

    /** {@code over} is the 0-based number of the over (the first over is 0). */
    public record Waiver(int over, UUID bowlerId, WaiverKind kind) {
    }

    public record OverShare(UUID bowlerId, int legalBalls) {
    }

    /** The over in progress: deliveries bowled, legal balls, who bowled how many (in order of first delivery), and whether a maiden is still possible. */
    public record Over(int deliveries, int legalBalls, List<OverShare> shares, boolean maidenBroken) {
        public Over {
            shares = List.copyOf(shares);
        }

        static Over fresh() {
            return new Over(0, 0, List.of(), false);
        }
    }

    public InningsState {
        battingXi = List.copyOf(battingXi);
        bowlingXi = List.copyOf(bowlingXi);
        batters = List.copyOf(batters);
        bowlers = List.copyOf(bowlers);
        fallOfWickets = List.copyOf(fallOfWickets);
        waivers = List.copyOf(waivers);
    }

    public static InningsState notStarted() {
        return new InningsState(Status.NOT_STARTED, null, 0, MatchRules.standard(), null, null, List.of(), List.of(), null, 0, 0, 0, Extras.NONE,
            null, null, List.of(), List.of(), null, null, Over.fresh(), false, false, false, List.of(), List.of());
    }

    public UUID striker() {
        return atBattingEnd;
    }

    public UUID nonStriker() {
        return atBowlersEnd;
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }

    public int legalBallsInOver() {
        return over.legalBalls();
    }

    public int ballsRemaining() {
        return overs * rules.ballsPerOver() - legalBalls;
    }

    /** "1.4" style: completed overs, a dot, balls in the current over. */
    public String oversText() {
        return oversText(legalBalls, rules.ballsPerOver());
    }

    public static String oversText(int legalBalls, int ballsPerOver) {
        return (legalBalls / ballsPerOver) + "." + (legalBalls % ballsPerOver);
    }

    public Optional<BatterLine> batter(UUID id) {
        return batters.stream().filter(b -> b.playerId().equals(id)).findFirst();
    }

    public Optional<BowlerLine> bowler(UUID id) {
        return bowlers.stream().filter(b -> b.playerId().equals(id)).findFirst();
    }

    /** Players of the batting side who have not batted yet, in batting order. */
    public List<UUID> yetToBat() {
        return battingXi.stream().filter(p -> batter(p).isEmpty()).toList();
    }
}
