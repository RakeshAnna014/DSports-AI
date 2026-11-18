package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.Dismissal;
import com.dsports.cricket.domain.rules.ScoringCommand;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The hand-scored two-over innings of the plan and a chase of it, as match-level commands. Lions bat first (players 10-n), Tigers bowl (players 20-n).
 * Lions make 22/2; Tigers chase 23 and win off the last ball.
 */
public final class GoldenMatch {

    public static final UUID LIONS = new UUID(1, 1);
    public static final UUID TIGERS = new UUID(2, 2);
    public static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

    public static UUID lion(int n) {
        return new UUID(10, n);
    }

    public static UUID tiger(int n) {
        return new UUID(20, n);
    }

    public static final MatchSetup SETUP = new MatchSetup(2, MatchRules.standard(), LIONS, TIGERS, LIONS,
        IntStream.rangeClosed(1, 11).mapToObj(GoldenMatch::lion).toList(), IntStream.rangeClosed(1, 11).mapToObj(GoldenMatch::tiger).toList());

    private GoldenMatch() {
    }

    private static ScoreCommand play(ScoringCommand c) {
        return new ScoreCommand.Play(c);
    }

    private static ScoreCommand ball(Delivery d) {
        return play(new ScoringCommand.Deliver(d));
    }

    /** Start of the first innings through its last ball. */
    public static List<ScoreCommand> lionsInnings() {
        return List.of(
            new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1)),
            ball(Delivery.wide(0)),
            ball(Delivery.runs(1)),
            ball(Delivery.noBallBoundary(4)),
            ball(Delivery.runs(2)),
            ball(Delivery.bye(1)),
            ball(Delivery.runs(1).out(Dismissal.runOut(lion(1), Dismissal.End.BATTING_END).by(tiger(3)))),
            play(new ScoringCommand.SelectBatter(lion(3))),
            ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED))),
            play(new ScoringCommand.SelectBatter(lion(4))),
            ball(Delivery.dot()),
            play(new ScoringCommand.SelectBowler(tiger(2))),
            ball(Delivery.runs(1)),
            ball(Delivery.runs(2)),
            play(new ScoringCommand.RetireBatter(lion(4), ScoringCommand.RetirementType.HURT)),
            play(new ScoringCommand.SelectBatter(lion(5))),
            play(new ScoringCommand.ChangeBowlerMidOver(tiger(3))),
            ball(Delivery.dot()),
            ball(Delivery.runs(1)),
            ball(Delivery.boundary(6)),
            ball(Delivery.runs(1)));
    }

    /** Tigers' innings: 23 needed, scored off the last ball. */
    public static List<ScoreCommand> tigersChase() {
        return List.of(
            new ScoreCommand.StartInnings(tiger(1), tiger(2), lion(1)),
            ball(Delivery.boundary(6)), ball(Delivery.boundary(4)), ball(Delivery.runs(1)), ball(Delivery.runs(1)), ball(Delivery.boundary(6)), ball(Delivery.dot()),
            play(new ScoringCommand.SelectBowler(lion(2))),
            ball(Delivery.wide(0)), ball(Delivery.runs(1)), ball(Delivery.dot()), ball(Delivery.dot()), ball(Delivery.dot()), ball(Delivery.dot()), ball(Delivery.runs(3)));
    }

    public static List<ScoreCommand> wholeMatch() {
        var all = new ArrayList<>(lionsInnings());
        all.addAll(tigersChase());
        return all;
    }

    /** A log of COMMAND entries for the commands, numbered from 1, one second apart from T0. */
    public static List<LogEntry> log(List<ScoreCommand> commands) {
        var entries = new ArrayList<LogEntry>();
        for (int i = 0; i < commands.size(); i++) {
            entries.add(LogEntry.command(i + 1, commands.get(i), new UUID(7, i + 1), lion(1), T0.plusSeconds(i), List.of()));
        }
        return entries;
    }
}
