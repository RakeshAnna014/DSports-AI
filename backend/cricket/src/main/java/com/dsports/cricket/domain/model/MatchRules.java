package com.dsports.cricket.domain.model;

import static com.dsports.cricket.domain.model.Rules.require;

/**
 * The rules a match is played under. Everything has a standard default and is configurable per match, because local cricket varies (tennis ball,
 * 8-a-side, last man stands, two-run no-balls). {@code maxOversPerBowler} null means the usual one fifth of the match, rounded up.
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

    /** The most overs one bowler may bowl: the configured limit, or a fifth of the match rounded up. */
    public int bowlerLimit(int overs) {
        return maxOversPerBowler != null ? maxOversPerBowler : (overs + 4) / 5;
    }
}
