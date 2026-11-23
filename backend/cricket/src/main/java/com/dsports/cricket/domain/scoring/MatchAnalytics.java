package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.Scorecard;
import com.dsports.cricket.domain.rules.ScoringCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * What a match looks like as charts and commentary, worked out from the log alone by replaying the commands in force: runs in each over (the Manhattan chart),
 * the running total at the end of each over (the worm and the run rate), the partnership for each wicket, and one commentary line for each ball. Pure code, no
 * clock, no store: the same log always gives the same answer, so an undo or a correction simply changes what is replayed. Names are resolved through the
 * functions passed in, so nothing here carries an id out: the result is names and numbers only and can be shown to anyone.
 *
 * <p>Nothing is estimated. A wagon wheel needs where each shot went, which the scorer does not record, so it is not computed here.
 */
public final class MatchAnalytics {

    private MatchAnalytics() {
    }

    /** One over: its number (the first over is 1), the runs and wickets in it, who bowled it, and whether it was a maiden (six legal balls, nothing off any of them). */
    public record OverRuns(int over, int runs, int wickets, String bowler, boolean maiden, boolean complete) {
    }

    /** The total after {@code over} overs (0 is the start); {@code legalBalls} is how many balls that is, so a part-over at the end is exact. */
    public record WormPoint(int over, int legalBalls, int runs, int wickets) {
    }

    /** The partnership that ended with the {@code wicket}-th wicket (the batter who went out first, then the partner), or the unbroken last one (the two still in). {@code balls} are legal balls bowled while they were together. */
    public record Partnership(int wicket, String batterA, String batterB, int runs, int balls, boolean unbroken) {
    }

    /** A line of the commentary. {@code over} reads "1.4" (the ball being bowled); {@code wicket} marks a dismissal, {@code boundary} a four or a six. */
    public record CommentaryLine(int innings, int seq, String over, String text, int runs, boolean wicket, boolean boundary) {
    }

    public record InningsAnalytics(int number, String battingTeam, String bowlingTeam, int runs, int wickets, String overs, Integer target, List<OverRuns> overRuns,
                                   List<WormPoint> worm, List<Partnership> partnerships) {
    }

    public record Analytics(List<InningsAnalytics> innings, List<CommentaryLine> commentary) {
        public Analytics {
            innings = List.copyOf(innings);
            commentary = List.copyOf(commentary);
        }
    }

    public static Analytics of(MatchSetup setup, List<LogEntry> entries, Function<UUID, String> player, Function<UUID, String> team) {
        var innings = new ArrayList<InningsAnalytics>();
        var commentary = new ArrayList<CommentaryLine>();
        var live = ScoringLog.effective(entries);
        var score = MatchScore.empty();
        Builder open = null;
        for (var entry : live) {
            var before = score.current();
            var outcome = MatchScoring.apply(setup, score, entry.command());
            if (!(outcome instanceof ScoreOutcome.Accepted accepted)) {
                throw new IllegalStateException("A logged command is no longer accepted: " + entry.command());
            }
            score = accepted.score();
            var command = entry.command();
            if (command.type() == ScoreCommand.Type.START_INNINGS) {
                if (open != null) {
                    innings.add(open.finish(score.innings().get(score.innings().size() - 2), team, player));
                }
                open = new Builder(score.innings().size());
                continue;
            }
            var after = score.current();
            if (open == null || before == null || after == null || !(command instanceof ScoreCommand.Play play)) {
                continue;
            }
            if (play.command() instanceof ScoringCommand.Deliver deliver) {
                open.delivery(before, after, deliver.delivery(), entry.seq(), player, commentary);
            } else if (play.command() instanceof ScoringCommand.RetireBatter retire && retire.type() == ScoringCommand.RetirementType.OUT) {
                open.wicketFell(before, after, player);
            }
        }
        if (open != null) {
            innings.add(open.finish(score.current(), team, player));
        }
        return new Analytics(innings, commentary);
    }

    /** The running work of one innings. */
    private static final class Builder {
        private final int number;
        private final List<OverRuns> overs = new ArrayList<>();
        private final List<WormPoint> worm = new ArrayList<>(List.of(new WormPoint(0, 0, 0, 0)));
        private final List<Partnership> partnerships = new ArrayList<>();
        private int overRuns;
        private int overWickets;
        private int overDeliveries;
        private boolean overAllLegal = true;
        private String overBowler;
        private int partnershipStartRuns;
        private int partnershipStartBalls;

        Builder(int number) {
            this.number = number;
        }

        void delivery(InningsState before, InningsState after, Delivery d, int seq, Function<UUID, String> player, List<CommentaryLine> commentary) {
            int bpo = before.rules().ballsPerOver();
            int added = after.runs() - before.runs();
            boolean wicket = after.wickets() > before.wickets();
            if (overDeliveries == 0) {
                overBowler = player.apply(before.currentBowler());
            }
            overRuns += added;
            overDeliveries++;
            overWickets += wicket ? 1 : 0;
            overAllLegal &= d.kind() == Delivery.Kind.NORMAL;
            commentary.add(line(before, after, d, seq, number, bpo, added, wicket, player));
            if (wicket) {
                wicketFell(before, after, player);
            }
            if (after.legalBalls() / bpo > before.legalBalls() / bpo) {
                closeOver(after.legalBalls() / bpo, after, true);
            }
        }

        /** The partnership between the two who were at the crease ends when the wicket fell. */
        void wicketFell(InningsState before, InningsState after, Function<UUID, String> player) {
            int fallNumber = after.fallOfWickets().size();
            var out = after.fallOfWickets().get(fallNumber - 1);
            var outId = out.batterId();
            var partner = outId.equals(before.atBattingEnd()) ? before.atBowlersEnd() : before.atBattingEnd();
            partnerships.add(new Partnership(fallNumber, player.apply(outId), partner == null ? null : player.apply(partner), out.runs() - partnershipStartRuns, out.legalBalls() - partnershipStartBalls, false));
            partnershipStartRuns = out.runs();
            partnershipStartBalls = out.legalBalls();
        }

        private void closeOver(int overNumber, InningsState after, boolean complete) {
            int bpo = after.rules().ballsPerOver();
            overs.add(new OverRuns(overNumber, overRuns, overWickets, overBowler, complete && overAllLegal && overRuns == 0 && overDeliveries == bpo, complete));
            worm.add(new WormPoint(overNumber, after.legalBalls(), after.runs(), after.wickets()));
            overRuns = 0;
            overWickets = 0;
            overDeliveries = 0;
            overAllLegal = true;
        }

        InningsAnalytics finish(InningsState last, Function<UUID, String> team, Function<UUID, String> player) {
            if (overDeliveries > 0) {
                int bpo = last.rules().ballsPerOver();
                overs.add(new OverRuns(last.legalBalls() / bpo + 1, overRuns, overWickets, overBowler, false, false));
                worm.add(new WormPoint(last.legalBalls() / bpo + 1, last.legalBalls(), last.runs(), last.wickets()));
            }
            var all = new ArrayList<>(partnerships);
            boolean someoneAtBothEnds = last.atBattingEnd() != null && last.atBowlersEnd() != null;
            if (someoneAtBothEnds && last.runs() - partnershipStartRuns >= 0 && (last.legalBalls() > partnershipStartBalls || last.runs() > partnershipStartRuns)) {
                all.add(new Partnership(last.fallOfWickets().size() + 1, player.apply(last.atBattingEnd()), player.apply(last.atBowlersEnd()), last.runs() - partnershipStartRuns, last.legalBalls() - partnershipStartBalls, true));
            }
            return new InningsAnalytics(number, team.apply(last.battingTeamId()), team.apply(last.bowlingTeamId()), last.runs(), last.wickets(), last.oversText(), last.target(), overs, worm, all);
        }
    }

    /** The words of one ball: "1.4 Tarun to Arun: FOUR", "0.3 Tarun to Bala: OUT, b Tarun", "0.5 Wide". Deterministic: the same ball always reads the same. */
    private static CommentaryLine line(InningsState before, InningsState after, Delivery d, int seq, int innings, int bpo, int added, boolean wicket, Function<UUID, String> player) {
        var over = (before.legalBalls() / bpo) + "." + (before.legalBalls() % bpo + 1);
        var bowler = player.apply(before.currentBowler());
        var striker = player.apply(before.atBattingEnd());
        boolean boundary = d.boundary();
        String what;
        if (d.kind() == Delivery.Kind.WIDE) {
            what = "wide" + (d.runs() > 0 ? " and " + plural(d.runs(), "run") : "");
        } else if (d.kind() == Delivery.Kind.NO_BALL) {
            what = "no ball" + (d.runs() > 0 ? ", " + (d.runsType() == Delivery.RunsType.BAT ? plural(d.runs(), "run") : plural(d.runs(), d.runsType() == Delivery.RunsType.BYE ? "bye" : "leg bye")) : "") + "; free hit next";
        } else if (d.runsType() == Delivery.RunsType.BYE) {
            what = plural(d.runs(), "bye");
        } else if (d.runsType() == Delivery.RunsType.LEG_BYE) {
            what = plural(d.runs(), "leg bye");
        } else {
            what = d.runs() == 0 ? "no run" : boundary ? (d.runs() == 4 ? "FOUR" : "SIX") : plural(d.runs(), "run");
        }
        var text = d.kind() == Delivery.Kind.NORMAL || d.kind() == Delivery.Kind.NO_BALL ? bowler + " to " + striker + ": " + what : bowler + ": " + what;
        if (wicket) {
            var outId = after.fallOfWickets().get(after.fallOfWickets().size() - 1).batterId();
            var line = after.batter(outId).map(l -> Scorecard.howOut(l, player)).orElse("out");
            text += ". OUT! " + player.apply(outId) + ", " + line + " (" + after.runs() + "/" + after.wickets() + ")";
        }
        return new CommentaryLine(innings, seq, over, text, added, wicket, boundary);
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
