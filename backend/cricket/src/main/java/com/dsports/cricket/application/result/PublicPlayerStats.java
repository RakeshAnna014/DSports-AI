package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.scoring.CareerStats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A player's career, for anyone: the name and numbers only (no phone, no account, no team). One {@link Line} for each ball type the player has a finished match in; tennis-ball and
 * leather-ball cricket are never added together. Every figure is computed from the stored match rows by {@link CareerStats#total}, and a ratio that cannot be worked out (a batting
 * average with no dismissal, an economy with no ball bowled) is {@code null}, never zero.
 */
public record PublicPlayerStats(String name, List<Line> lines) {

    public PublicPlayerStats {
        lines = List.copyOf(lines);
    }

    public record Line(BallType ballType, int matches, Batting batting, Bowling bowling, Fielding fielding) {
    }

    /** {@code highestNotOut} marks a highest score that was not out (printed 87*). */
    public record Batting(int innings, int runs, int balls, int notOuts, int highest, boolean highestNotOut, int fours, int sixes, int fifties, int hundreds, int ducks,
                          BigDecimal average, BigDecimal strikeRate) {
    }

    /** {@code overs} reads 12.3 (twelve overs and three balls) for a six-ball over. */
    public record Bowling(int innings, String overs, int runs, int wickets, int maidens, String best, int fiveWickets, BigDecimal average, BigDecimal economy, BigDecimal strikeRate) {
    }

    public record Fielding(int catches, int stumpings, int runOuts) {
    }

    public static Line line(CareerStats.Career c, int ballsPerOver) {
        int dismissals = c.inningsBatted() - c.notOuts();
        var batting = new Batting(c.inningsBatted(), c.runs(), c.balls(), c.notOuts(), c.highest(), c.highestNotOut(), c.fours(), c.sixes(), c.fifties(), c.hundreds(), c.ducks(),
            ratio(c.runs(), dismissals, 1), ratio(c.runs(), c.balls(), 100));
        var bowling = new Bowling(c.inningsBowled(), overs(c.ballsBowled(), ballsPerOver), c.runsConceded(), c.wickets(), c.maidens(),
            c.inningsBowled() == 0 ? null : c.bestWickets() + "/" + c.bestRuns(), c.fiveWickets(), ratio(c.runsConceded(), c.wickets(), 1), ratio(c.runsConceded(), c.ballsBowled(), ballsPerOver),
            ratio(c.ballsBowled(), c.wickets(), 1));
        return new Line(c.ballType(), c.matches(), batting, bowling, new Fielding(c.catches(), c.stumpings(), c.runOuts()));
    }

    static String overs(int balls, int ballsPerOver) {
        return balls / ballsPerOver + "." + balls % ballsPerOver;
    }

    /** {@code numerator * scale / denominator}, two decimals, or null when the denominator is zero. */
    static BigDecimal ratio(int numerator, int denominator, int scale) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf((long) numerator * scale).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }
}
