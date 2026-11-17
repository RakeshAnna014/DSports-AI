package com.dsports.cricket.domain.model;

import static com.dsports.cricket.domain.model.Rules.require;

/**
 * The rules a match is played under. Everything has a standard default and is configurable per match, because local cricket varies (tennis ball,
 * 8-a-side, last man stands, two-run no-balls). {@code maxOversPerBowler} null means the default limit, which grows for small sides (see {@link #bowlerLimit}).
 */
public record MatchRules(int ballsPerOver, int playersPerSide, Integer maxOversPerBowler, int widePenalty, int noBallPenalty, boolean freeHitEnabled, boolean lastManStands) {

    public static MatchRules standard() {
        return new MatchRules(6, 11, null, 1, 1, true, false);
    }

    /** Throws a validation error unless the rules make sense for a match of {@code overs} overs. */
    public void validate(int overs) {
        require(ballsPerOver >= 4 && ballsPerOver <= 10, "Balls per over must be between 4 and 10");
        require(playersPerSide >= 2 && playersPerSide <= 11, "Players per side must be between 2 and 11");
        require(maxOversPerBowler == null || (maxOversPerBowler >= 1 && maxOversPerBowler <= overs), "Overs per bowler must be between 1 and the match length");
        require(widePenalty >= 0 && widePenalty <= 5 && noBallPenalty >= 0 && noBallPenalty <= 5, "Wide and no-ball penalties must be between 0 and 5 runs");
    }

    /**
     * The most overs one bowler may bowl: the configured limit, or by default the larger of a fifth of the match and the match shared between all
     * the bowlers a side can field (players per side minus the wicket-keeper), each rounded up. An 11-a-side match keeps the usual fifth;
     * a 4-a-side 20-over match allows 7, so a small side can still complete its overs.
     */
    public int bowlerLimit(int overs) {
        if (maxOversPerBowler != null) {
            return maxOversPerBowler;
        }
        return Math.max(ceilDiv(overs, 5), ceilDiv(overs, playersPerSide - 1));
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
