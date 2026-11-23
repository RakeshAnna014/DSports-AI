package com.dsports.cricket.domain.tournament;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The points table of a league, a pure function over finished fixtures (the same results always give the same table, so an undo that changes a result changes the table).
 *
 * <p>Points: a win, a tie and a no result (abandoned, or ended with no result) are worth what the tournament's rules say (2, 1, 1 by default); a loss is 0. Net run rate is
 * runs scored per over faced minus runs conceded per over bowled over the matches that produced a result (a win, a loss or a tie). A side that was bowled out is counted as
 * having faced its whole quota of balls, not the balls it actually used; a no-result match is left out of net run rate. The quota is per side so a shortened (rain-affected)
 * chase uses the overs it was really given. A team with no ball faced has a net run rate of 0, not a division by zero.
 *
 * <p>Order: points, then wins, then net run rate, then the head-to-head (wins in the matches between two teams that are still level, when exactly two are), then the
 * team id so the order is always the same. Each step is written here because there is no standard that every league uses.
 */
public final class PointsTable {

    private PointsTable() {
    }

    public record Rules(int win, int tie, int noResult) {
        public static Rules standard() {
            return new Rules(2, 1, 1);
        }
    }

    /** One innings as it counts for run rate: runs scored, balls actually faced, whether the side was all out and the balls it was entitled to. */
    public record Side(UUID team, int runs, int ballsFaced, boolean allOut, int quotaBalls) {
        int ballsCounted() {
            return allOut ? quotaBalls : ballsFaced;
        }
    }

    public enum Outcome { RESULT, TIE, NO_RESULT }

    /** A finished fixture. {@code winner} is null for a tie or no result; {@code first} and {@code second} are null for a match with no result that had no innings. */
    public record Played(UUID teamA, UUID teamB, Outcome outcome, UUID winner, Side first, Side second) {
    }

    public record Row(UUID team, int played, int won, int lost, int tied, int noResult, int points, BigDecimal netRunRate) {
    }

    private static final class Acc {
        int played, won, lost, tied, noResult, points;
        long runsFor, ballsFor, runsAgainst, ballsAgainst;
    }

    public static List<Row> compute(List<UUID> teams, List<Played> results, Rules rules, int ballsPerOver) {
        var acc = new LinkedHashMap<UUID, Acc>();
        teams.forEach(t -> acc.put(t, new Acc()));
        var headToHead = new HashMap<UUID, Map<UUID, Integer>>();
        for (var r : results) {
            var a = acc.get(r.teamA());
            var b = acc.get(r.teamB());
            if (a == null || b == null) {
                continue; // a result between teams that are not in the tournament (a withdrawn team's) is not in this table
            }
            a.played++;
            b.played++;
            switch (r.outcome()) {
                case NO_RESULT -> {
                    a.noResult++;
                    b.noResult++;
                    a.points += rules.noResult();
                    b.points += rules.noResult();
                }
                case TIE -> {
                    a.tied++;
                    b.tied++;
                    a.points += rules.tie();
                    b.points += rules.tie();
                    runRate(acc, r);
                }
                case RESULT -> {
                    var winner = r.winner().equals(r.teamA()) ? a : b;
                    var loser = winner == a ? b : a;
                    winner.won++;
                    winner.points += rules.win();
                    loser.lost++;
                    headToHead.computeIfAbsent(r.winner(), k -> new HashMap<>()).merge(r.winner().equals(r.teamA()) ? r.teamB() : r.teamA(), 1, Integer::sum);
                    runRate(acc, r);
                }
            }
        }
        var rows = new ArrayList<Row>();
        acc.forEach((team, x) -> rows.add(new Row(team, x.played, x.won, x.lost, x.tied, x.noResult, x.points, rate(x, ballsPerOver))));
        sortRows(rows, headToHead);
        return rows;
    }

    private static void runRate(Map<UUID, Acc> acc, Played r) {
        if (r.first() == null || r.second() == null) {
            return;
        }
        for (var pair : List.of(new Side[] {r.first(), r.second()}, new Side[] {r.second(), r.first()})) {
            var own = pair[0];
            var other = pair[1];
            var x = acc.get(own.team());
            if (x != null) {
                x.runsFor += own.runs();
                x.ballsFor += own.ballsCounted();
                x.runsAgainst += other.runs();
                x.ballsAgainst += other.ballsCounted();
            }
        }
    }

    private static BigDecimal rate(Acc x, int bpo) {
        if (x.ballsFor == 0 || x.ballsAgainst == 0) {
            return BigDecimal.ZERO.setScale(3);
        }
        var scored = BigDecimal.valueOf(x.runsFor * bpo).divide(BigDecimal.valueOf(x.ballsFor), 10, RoundingMode.HALF_UP);
        var conceded = BigDecimal.valueOf(x.runsAgainst * bpo).divide(BigDecimal.valueOf(x.ballsAgainst), 10, RoundingMode.HALF_UP);
        return scored.subtract(conceded).setScale(3, RoundingMode.HALF_UP);
    }

    /** Points, wins, net run rate, then the team id; afterwards a pair that is still level is put in head-to-head order (a group of three or more level teams stays in id order, which is stable and fair to none). */
    private static void sortRows(List<Row> rows, Map<UUID, Map<UUID, Integer>> headToHead) {
        rows.sort(Comparator.comparingInt(Row::points).reversed().thenComparing(Comparator.comparingInt(Row::won).reversed())
            .thenComparing(Comparator.comparing(Row::netRunRate).reversed()).thenComparing(Row::team));
        int i = 0;
        while (i < rows.size()) {
            int j = i + 1;
            while (j < rows.size() && level(rows.get(i), rows.get(j))) {
                j++;
            }
            if (j - i == 2) {
                var p = rows.get(i);
                var q = rows.get(i + 1);
                int pOverQ = headToHead.getOrDefault(p.team(), Map.of()).getOrDefault(q.team(), 0);
                int qOverP = headToHead.getOrDefault(q.team(), Map.of()).getOrDefault(p.team(), 0);
                if (qOverP > pOverQ) {
                    rows.set(i, q);
                    rows.set(i + 1, p);
                }
            }
            i = j;
        }
    }

    private static boolean level(Row p, Row q) {
        return p.points() == q.points() && p.won() == q.won() && p.netRunRate().compareTo(q.netRunRate()) == 0;
    }
}
