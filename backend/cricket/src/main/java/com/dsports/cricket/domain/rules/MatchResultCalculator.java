package com.dsports.cricket.domain.rules;

import java.util.UUID;

/**
 * The result of a match from its two innings. Computed from the innings every time and never stored on its own, so an undo that changes a ball
 * re-derives it. A manual revised target (the scorer's number) is already in the second innings' {@code target}.
 */
public final class MatchResultCalculator {

    public enum Type { IN_PROGRESS, WON_BY_RUNS, WON_BY_WICKETS, TIE }

    /** {@code margin} is runs or wickets; {@code ballsRemaining} is only set for a win by wickets. {@code winnerTeamId} is null unless someone won. */
    public record Result(Type type, UUID winnerTeamId, int margin, int ballsRemaining) {
    }

    private MatchResultCalculator() {
    }

    public static Result compute(InningsState first, InningsState second) {
        if (!first.isComplete() || second.target() == null) {
            throw new IllegalArgumentException("The result needs a completed first innings and a second innings with a target");
        }
        if (!second.isComplete()) {
            return new Result(Type.IN_PROGRESS, null, 0, 0);
        }
        int target = second.target();
        if (second.runs() >= target) {
            int inHand = Math.max(0, wicketsAvailable(second) - second.wickets());
            return new Result(Type.WON_BY_WICKETS, second.battingTeamId(), inHand, Math.max(0, second.ballsRemaining()));
        }
        if (second.runs() == target - 1) {
            return new Result(Type.TIE, null, 0, 0);
        }
        return new Result(Type.WON_BY_RUNS, first.battingTeamId(), target - 1 - second.runs(), 0);
    }

    /** Wickets the batting side can lose: one fewer than its players, or all of them with last man stands. */
    private static int wicketsAvailable(InningsState s) {
        return s.rules().lastManStands() ? s.battingXi().size() : s.battingXi().size() - 1;
    }
}
