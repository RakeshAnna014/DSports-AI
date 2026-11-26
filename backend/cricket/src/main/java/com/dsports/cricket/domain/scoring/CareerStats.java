package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.InningsState.BatterLine;
import com.dsports.cricket.domain.rules.InningsState.BatterStatus;
import com.dsports.cricket.domain.rules.InningsState.BowlerLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Career statistics, worked out from scored matches and nothing else. Two pure steps: {@link #ofMatch} turns one finished match into what each player did in it
 * (a {@link Match} row per player), and {@link #total} adds the rows of one player and one ball type into a career line. Nothing here reads a clock or a store, so
 * the stored rows are only ever a cache of this function over the log: they can be thrown away and rebuilt and give the same numbers.
 *
 * <p>Only a COMPLETED match counts. A match abandoned or ended with no result is a partial scorecard that is not a result, and counting it would make a
 * player's record depend on the weather, so it is left out (recorded in docs/plans/DECISIONS-LOG.md). Tennis-ball and leather-ball cricket are never added
 * together.
 *
 * <p>Conventions, each pinned by a test: a batter who came to the crease has batted an innings (out or not out; retired hurt is not out, retired out is out);
 * only the bowler's credited wickets are his wickets (the scorecard already leaves out run outs and the other dismissals that are not the bowler's); a caught
 * and bowled is a catch for the bowler; wides and no balls are not balls bowled by the bowler for his over count but their runs are conceded (as on the
 * scorecard); a player who is in either playing XI has played the match even if he neither batted nor bowled.
 */
public final class CareerStats {

    private CareerStats() {
    }

    /** What one player did in one match. A ball type is carried so the career can keep tennis and leather apart. */
    public record Match(UUID playerId, BallType ballType, int inningsBatted, int runs, int balls, int notOuts, int fours, int sixes, int highest, boolean highestNotOut,
                        int fifties, int hundreds, int ducks,
                        int inningsBowled, int ballsBowled, int runsConceded, int wickets, int maidens, int bestWickets, int bestRuns,
                        int catches, int stumpings, int runOuts) {
    }

    /** A player's career in one ball type: the sum of his match rows, with the ones that are not sums (highest score, best bowling) taken as the best of them. */
    public record Career(UUID playerId, BallType ballType, int matches, int inningsBatted, int runs, int balls, int notOuts, int fours, int sixes, int highest,
                         boolean highestNotOut, int fifties, int hundreds, int ducks,
                         int inningsBowled, int ballsBowled, int runsConceded, int wickets, int maidens, int bestWickets, int bestRuns, int fiveWickets,
                         int catches, int stumpings, int runOuts) {
    }

    /** The rows of one finished match, one for each player who played it; nothing for a match that is not COMPLETED. */
    public static List<Match> ofMatch(MatchSetup setup, MatchScore score, BallType ballType) {
        if (score.status() != MatchStatus.COMPLETED) {
            return List.of();
        }
        var rows = new LinkedHashMap<UUID, Acc>();
        for (var id : setup.xiA()) {
            rows.computeIfAbsent(id, Acc::new);
        }
        for (var id : setup.xiB()) {
            rows.computeIfAbsent(id, Acc::new);
        }
        for (var innings : score.innings()) {
            for (var line : innings.batters()) {
                rows.computeIfAbsent(line.playerId(), Acc::new).batted(line);
                credit(rows, line);
            }
            for (var line : innings.bowlers()) {
                rows.computeIfAbsent(line.playerId(), Acc::new).bowled(line);
            }
        }
        var out = new ArrayList<Match>(rows.size());
        rows.values().forEach(acc -> out.add(acc.row(ballType)));
        return out;
    }

    /** The fielder gets the catch, the stumping or the run out; a caught and bowled is the bowler's catch. */
    private static void credit(Map<UUID, Acc> rows, BatterLine line) {
        if (line.status() != BatterStatus.OUT || line.dismissal() == null) {
            return;
        }
        UUID fielder = line.fielderId();
        if (fielder == null) {
            return;
        }
        var acc = rows.computeIfAbsent(fielder, Acc::new);
        switch (line.dismissal()) {
            case CAUGHT -> acc.catches++;
            case STUMPED -> acc.stumpings++;
            case RUN_OUT -> acc.runOuts++;
            default -> {
            }
        }
    }

    /** A player's career in one ball type from his rows (rows of the other ball type are ignored). Empty when he has no row of that type. */
    public static Optional<Career> total(UUID playerId, BallType ballType, List<Match> rows) {
        int matches = 0, inningsBatted = 0, runs = 0, balls = 0, notOuts = 0, fours = 0, sixes = 0, highest = -1, fifties = 0, hundreds = 0, ducks = 0;
        int inningsBowled = 0, ballsBowled = 0, conceded = 0, wickets = 0, maidens = 0, bestW = -1, bestR = 0, five = 0, catches = 0, stumpings = 0, runOuts = 0;
        boolean highestNotOut = false;
        for (var m : rows) {
            if (!m.playerId().equals(playerId) || m.ballType() != ballType) {
                continue;
            }
            matches++;
            inningsBatted += m.inningsBatted();
            runs += m.runs();
            balls += m.balls();
            notOuts += m.notOuts();
            fours += m.fours();
            sixes += m.sixes();
            fifties += m.fifties();
            hundreds += m.hundreds();
            ducks += m.ducks();
            if (m.inningsBatted() > 0 && (m.highest() > highest || (m.highest() == highest && m.highestNotOut() && !highestNotOut))) {
                highest = m.highest();
                highestNotOut = m.highestNotOut();
            }
            inningsBowled += m.inningsBowled();
            ballsBowled += m.ballsBowled();
            conceded += m.runsConceded();
            wickets += m.wickets();
            maidens += m.maidens();
            if (m.inningsBowled() > 0 && betterBowling(m.bestWickets(), m.bestRuns(), bestW, bestR)) {
                bestW = m.bestWickets();
                bestR = m.bestRuns();
            }
            five += m.bestWickets() >= 5 ? 1 : 0;
            catches += m.catches();
            stumpings += m.stumpings();
            runOuts += m.runOuts();
        }
        if (matches == 0) {
            return Optional.empty();
        }
        return Optional.of(new Career(playerId, ballType, matches, inningsBatted, runs, balls, notOuts, fours, sixes, Math.max(highest, 0), highestNotOut, fifties, hundreds, ducks,
            inningsBowled, ballsBowled, conceded, wickets, maidens, Math.max(bestW, 0), bestR, five, catches, stumpings, runOuts));
    }

    /** More wickets is better; the same wickets for fewer runs is better. */
    static boolean betterBowling(int wickets, int runs, int bestWickets, int bestRuns) {
        return bestWickets < 0 || wickets > bestWickets || (wickets == bestWickets && runs < bestRuns);
    }

    /** The work of one player in one match, added to as the innings are read. */
    private static final class Acc {
        private final UUID id;
        private int inningsBatted, runs, balls, notOuts, fours, sixes, highest = -1, fifties, hundreds, ducks;
        private boolean highestNotOut;
        private int inningsBowled, ballsBowled, conceded, wickets, maidens, bestW = -1, bestR;
        private int catches, stumpings, runOuts;

        Acc(UUID id) {
            this.id = id;
        }

        void batted(BatterLine line) {
            inningsBatted++;
            runs += line.runs();
            balls += line.balls();
            fours += line.fours();
            sixes += line.sixes();
            boolean notOut = line.status() == BatterStatus.NOT_OUT || line.status() == BatterStatus.RETIRED_HURT;
            notOuts += notOut ? 1 : 0;
            fifties += line.runs() >= 50 && line.runs() < 100 ? 1 : 0;
            hundreds += line.runs() >= 100 ? 1 : 0;
            ducks += line.runs() == 0 && !notOut ? 1 : 0;
            if (line.runs() > highest || (line.runs() == highest && notOut && !highestNotOut)) {
                highest = line.runs();
                highestNotOut = notOut;
            }
        }

        void bowled(BowlerLine line) {
            if (line.legalBalls() == 0 && line.runs() == 0 && line.wides() == 0 && line.noBalls() == 0) {
                return;
            }
            inningsBowled++;
            ballsBowled += line.legalBalls();
            conceded += line.runs();
            wickets += line.wickets();
            maidens += line.maidens();
            if (betterBowling(line.wickets(), line.runs(), bestW, bestR)) {
                bestW = line.wickets();
                bestR = line.runs();
            }
        }

        Match row(BallType ballType) {
            return new Match(id, ballType, inningsBatted, runs, balls, notOuts, fours, sixes, Math.max(highest, 0), highestNotOut, fifties, hundreds, ducks,
                inningsBowled, ballsBowled, conceded, wickets, maidens, Math.max(bestW, 0), bestR, catches, stumpings, runOuts);
        }
    }
}
