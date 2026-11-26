package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.Delivery;
import com.dsports.cricket.domain.rules.Dismissal;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog.UndoMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static com.dsports.cricket.domain.scoring.GoldenMatch.lion;
import static com.dsports.cricket.domain.scoring.GoldenMatch.tiger;

/**
 * The hand-written vectors: the golden match scored as written, the same match with undos in every mode and place that matters, and scenarios aimed at
 * rules the random matches rarely reach. Each one is a named list of steps; the corpus runs them through the engine and writes what happened.
 */
final class VectorScenarios {

    record Scenario(String name, String description, MatchSetup setup, List<VectorStep> steps) {
    }

    private VectorScenarios() {
    }

    static List<Scenario> all() {
        return List.of(goldenMatch(), goldenMatchWithUndos(), goldenMatchRefusals(), goldenTie(), tinySides(), setupTooSmall());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ScoreCommand play(ScoringCommand c) {
        return new ScoreCommand.Play(c);
    }

    private static ScoreCommand ball(Delivery d) {
        return play(new ScoringCommand.Deliver(d));
    }

    private static List<VectorStep> steps(List<ScoreCommand> commands) {
        return commands.stream().map(VectorStep::of).toList();
    }

    private static MatchSetup setupOf(int players, MatchRules rules, int overs) {
        return new MatchSetup(overs, rules, GoldenMatch.LIONS, GoldenMatch.TIGERS, GoldenMatch.LIONS,
            IntStream.rangeClosed(1, players).mapToObj(GoldenMatch::lion).toList(), IntStream.rangeClosed(1, players).mapToObj(GoldenMatch::tiger).toList());
    }

    // ---- scenarios -------------------------------------------------------------------------------------------------

    private static Scenario goldenMatch() {
        return new Scenario("golden-match", "The hand-scored two-over innings of the plan and a chase that is won off the last ball (11 a side).", GoldenMatch.SETUP,
            steps(GoldenMatch.wholeMatch()));
    }

    /** The same match, with an undo after a plain ball, after a wicket and its new batter, in both modes, one with nothing to undo and one after the result. */
    private static Scenario goldenMatchWithUndos() {
        var c = GoldenMatch.wholeMatch();
        var steps = new ArrayList<VectorStep>();
        steps.add(VectorStep.undo(UndoMode.BALL));                       // nothing to undo yet
        steps.addAll(steps(c.subList(0, 4)));                            // start innings, wide, 1, no-ball four
        steps.add(VectorStep.undo(UndoMode.COMMAND));                    // takes off the no-ball four
        steps.add(VectorStep.of(c.get(3)));                              // and it is entered again
        steps.addAll(steps(c.subList(4, 9)));                            // 2, bye, run out, new batter, bowled
        steps.add(VectorStep.undo(UndoMode.BALL));                       // takes off the bowled ball only (no choice has followed it)
        steps.add(VectorStep.of(c.get(8)));                              // bowled again
        steps.addAll(steps(c.subList(9, 10)));                           // the next batter comes in
        steps.add(VectorStep.undo(UndoMode.BALL));                       // takes off the choice and the ball it followed
        steps.add(VectorStep.of(c.get(8)));
        steps.add(VectorStep.of(c.get(9)));
        steps.addAll(steps(c.subList(10, c.size())));                    // the rest of the match
        steps.add(VectorStep.undo(UndoMode.BALL));                       // after the result: takes off the winning ball
        steps.add(VectorStep.of(c.get(c.size() - 1)));                   // and it is entered again
        return new Scenario("golden-match-with-undos", "The golden match with undos in both modes: nothing to undo, a plain ball, a wicket with and without its new batter, and the winning ball.",
            GoldenMatch.SETUP, steps);
    }

    /** The golden match with commands the engine must refuse, each at the moment it is wrong, so a refusal is checked to change nothing. */
    private static Scenario goldenMatchRefusals() {
        var c = GoldenMatch.wholeMatch();
        var steps = new ArrayList<VectorStep>();
        steps.add(VectorStep.of(ball(Delivery.runs(1))));                                        // INNINGS_NOT_STARTED
        steps.add(VectorStep.of(play(new ScoringCommand.ReviseTarget(30))));                      // INNINGS_NOT_STARTED
        steps.add(VectorStep.of(c.get(0)));                                                      // the innings starts
        steps.add(VectorStep.of(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1))));     // PREVIOUS_INNINGS_NOT_COMPLETE
        steps.add(VectorStep.of(play(new ScoringCommand.ReviseTarget(30))));                      // NO_TARGET_TO_REVISE
        steps.add(VectorStep.of(ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.BOWLED).by(tiger(2))))));   // FIELDER_NOT_ALLOWED
        steps.add(VectorStep.of(ball(Delivery.dot().out(Dismissal.striker(Dismissal.Type.CAUGHT).by(lion(5))))));    // FIELDER_NOT_IN_XI
        steps.addAll(steps(c.subList(1, 7)));                                                    // up to the run out: a batter is needed
        steps.add(VectorStep.of(ball(Delivery.dot())));                                          // NEEDS_NEW_BATTER
        steps.add(VectorStep.of(play(new ScoringCommand.SelectBatter(tiger(1)))));               // BATTER_NOT_IN_XI
        steps.addAll(steps(c.subList(7, 11)));                                                   // batter, bowled, batter, dot: the over is done
        steps.add(VectorStep.of(play(new ScoringCommand.SelectBowler(lion(1)))));                // BOWLER_NOT_IN_XI
        steps.add(VectorStep.of(play(new ScoringCommand.SelectBowler(tiger(1)))));               // BOWLER_BOWLED_PREVIOUS_OVER
        steps.addAll(steps(c.subList(11, 21)));                                                  // the rest of the first innings
        steps.add(VectorStep.of(ball(Delivery.dot())));                                          // INNINGS_COMPLETE
        steps.addAll(steps(c.subList(21, 22)));                                                  // the chase starts: 23 to win
        steps.add(VectorStep.of(play(new ScoringCommand.ReviseTarget(0))));                       // INVALID_TARGET
        steps.addAll(steps(c.subList(22, c.size())));                                            // the chase is won off the last ball
        steps.add(VectorStep.of(play(new ScoringCommand.ReviseTarget(40))));                      // TARGET_ALREADY_REACHED
        steps.add(VectorStep.of(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1))));     // ALL_INNINGS_PLAYED
        steps.add(VectorStep.of(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.ABANDONED)));   // MATCH_COMPLETE
        return new Scenario("golden-match-refusals", "The golden match with refused commands at the moments they are wrong: before the start, mid-over, when a batter or bowler is needed, after the innings, and after the result.",
            GoldenMatch.SETUP, steps);
    }

    /** The chase falls one run short of the target by one run: scores level, so the match is a tie. */
    private static Scenario goldenTie() {
        var c = new ArrayList<>(GoldenMatch.wholeMatch());
        c.set(c.size() - 1, ball(Delivery.runs(2)));
        return new Scenario("golden-tie", "The golden match with the last ball of the chase scoring 2 instead of 3: the scores are level and the result is a tie.", GoldenMatch.SETUP, steps(c));
    }

    /** Two a side, so there is nobody left to bat after the openers and the scorer may end the innings as "no batters left"; then the match is ended by hand. */
    private static Scenario tinySides() {
        var setup = setupOf(2, new MatchRules(6, 2, null, 1, 1, true, false), 2);
        var steps = new ArrayList<VectorStep>();
        steps.add(VectorStep.of(new ScoreCommand.StartInnings(lion(1), lion(2), tiger(1))));
        steps.add(VectorStep.of(play(new ScoringCommand.EndInnings(com.dsports.cricket.domain.rules.InningsState.EndReason.ALL_OUT))));          // CANNOT_END_YET
        steps.add(VectorStep.of(play(new ScoringCommand.EndInnings(com.dsports.cricket.domain.rules.InningsState.EndReason.NO_BATTERS_LEFT))));
        steps.add(VectorStep.of(new ScoreCommand.StartInnings(tiger(1), tiger(2), lion(1))));
        steps.add(VectorStep.of(new ScoreCommand.EndMatch(ScoreCommand.EndMatch.Reason.NO_RESULT)));
        steps.add(VectorStep.of(ball(Delivery.dot())));                                                                                           // MATCH_ENDED
        steps.add(VectorStep.undo(UndoMode.COMMAND));                                                                                             // takes the end off again
        steps.add(VectorStep.of(play(new ScoringCommand.EndInnings(com.dsports.cricket.domain.rules.InningsState.EndReason.DECLARED))));
        steps.add(VectorStep.undo(UndoMode.COMMAND));
        steps.add(VectorStep.undo(UndoMode.COMMAND));
        return new Scenario("tiny-sides", "Two a side: ending an innings as no batters left, declaring, ending the match by hand, and undoing back past the end.", setup, steps);
    }

    /** One player a side: the toss and XIs are not really done, and the engine refuses to start. */
    private static Scenario setupTooSmall() {
        var setup = setupOf(1, MatchRules.standard(), 2);
        return new Scenario("setup-too-small", "A setup with a single player a side: starting an innings is refused as an invalid setup.", setup,
            List.of(VectorStep.of(new ScoreCommand.StartInnings(lion(1), lion(1), tiger(1))), VectorStep.undo(UndoMode.BALL)));
    }

    /**
     * Words of the vocabulary that no step can reach through the scoring route, by vocabulary list and word, each with the reason. The coverage test accepts
     * exactly these and nothing else.
     */
    static Map<String, Map<String, String>> notCoverable() {
        var violations = new LinkedHashMap<String, String>();
        violations.put("INNINGS_ALREADY_STARTED", "the match level only asks the engine to start an innings that has not started");
        violations.put("INVALID_DELIVERY", "the request body cannot leave out the delivery or the type of a dismissal: that is a 400 before the engine");
        violations.put("INVALID_RETIREMENT", "the request body requires the type of retirement: a missing one is a 400 before the engine");
        var statuses = new LinkedHashMap<String, String>();
        statuses.put("SCHEDULED", "a match is scheduled before the toss; a scored match is never in that state");
        var results = new LinkedHashMap<String, String>();
        results.put("IN_PROGRESS", "a result is only worked out for a completed match");
        var all = new LinkedHashMap<String, Map<String, String>>();
        all.put("violations", violations);
        all.put("matchStatuses", statuses);
        all.put("resultTypes", results);
        return all;
    }
}
