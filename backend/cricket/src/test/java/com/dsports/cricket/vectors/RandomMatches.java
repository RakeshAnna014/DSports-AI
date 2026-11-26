package com.dsports.cricket.vectors;

import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.rules.RandomScorer;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.scoring.GoldenMatch;
import com.dsports.cricket.domain.scoring.MatchSetup;
import com.dsports.cricket.domain.scoring.ScoreCommand;
import com.dsports.cricket.domain.scoring.ScoringLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Seeded random matches at match level: a random setup, then commands that are mostly sensible and often not (the engine's own random scorer for the innings,
 * plus starting innings, ending the match and undoing). The same seed always gives the same match. The commands are written down in the files, so the
 * other language never has to reproduce this generator or Java's random numbers.
 */
final class RandomMatches {

    static final int SEEDS = 120;
    private static final int MIN_STEPS = 90;
    private static final int EXTRA_STEPS = 80;
    private static final int STEPS_AFTER_THE_END = 4;

    record Match(long seed, MatchSetup setup, List<VectorStep> steps) {
    }

    private RandomMatches() {
    }

    static Match generate(long seed) {
        var random = new Random(seed);
        int players = random.nextInt(4) == 0 ? 2 + random.nextInt(2) : 2 + random.nextInt(10);
        int overs = 1 + random.nextInt(5);
        var rules = new MatchRules(4 + random.nextInt(5), players, random.nextInt(3) == 0 ? 1 : null, random.nextInt(3), random.nextInt(3), random.nextInt(5) != 0, random.nextInt(4) == 0);
        var xiA = IntStream.rangeClosed(1, players).mapToObj(GoldenMatch::lion).toList();
        var xiB = IntStream.rangeClosed(1, players).mapToObj(GoldenMatch::tiger).toList();
        var battingFirst = random.nextBoolean() ? GoldenMatch.LIONS : GoldenMatch.TIGERS;
        var setup = new MatchSetup(overs, rules, GoldenMatch.LIONS, GoldenMatch.TIGERS, battingFirst, xiA, xiB);

        var runner = new VectorRunner(setup);
        var steps = new ArrayList<VectorStep>();
        int limit = MIN_STEPS + random.nextInt(EXTRA_STEPS);
        int afterEnd = 0;
        while (steps.size() < limit && afterEnd < STEPS_AFTER_THE_END) {
            var step = next(random, setup, runner);
            steps.add(step);
            switch (step) {
                case VectorStep.Command c -> runner.command(c.command());
                case VectorStep.Undo u -> runner.undo(u.mode());
            }
            if (runner.score().isFinished()) {
                afterEnd++;
            }
        }
        return new Match(seed, setup, steps);
    }

    private static VectorStep next(Random r, MatchSetup setup, VectorRunner runner) {
        var score = runner.score();
        var current = score.current();
        int p = r.nextInt(100);
        if (p < 5) {
            return VectorStep.undo(r.nextInt(10) < 7 ? ScoringLog.UndoMode.BALL : ScoringLog.UndoMode.COMMAND);
        }
        if (p < 7) {
            return VectorStep.of(new ScoreCommand.EndMatch(r.nextBoolean() ? ScoreCommand.EndMatch.Reason.ABANDONED : ScoreCommand.EndMatch.Reason.NO_RESULT));
        }
        boolean needStart = current == null || current.isComplete();
        if (needStart && r.nextInt(100) < 85) {
            return VectorStep.of(startInnings(r, setup, score.innings().size()));
        }
        if (current == null || r.nextInt(100) < 2) {
            return VectorStep.of(startInnings(r, setup, score.innings().size()));
        }
        return VectorStep.of(new ScoreCommand.Play(wireSafe(RandomScorer.nextCommand(r, current), r)));
    }

    private static ScoreCommand startInnings(Random r, MatchSetup setup, int inningsSoFar) {
        var batting = inningsSoFar == 0 ? setup.battingFirstTeamId() : setup.otherTeam(setup.battingFirstTeamId());
        var bowling = setup.otherTeam(batting);
        var batters = setup.xi(batting);
        var bowlers = setup.xi(bowling);
        var striker = batters.get(r.nextInt(batters.size()));
        var nonStriker = r.nextInt(10) == 0 ? striker : batters.get(r.nextInt(batters.size()));
        return new ScoreCommand.StartInnings(striker, nonStriker, bowlers.get(r.nextInt(bowlers.size())));
    }

    /** A retirement without a type is a 400 at the door (the body has no way to say it), never a rule refusal, so a vector must not contain one. */
    private static ScoringCommand wireSafe(ScoringCommand command, Random r) {
        if (command instanceof ScoringCommand.RetireBatter b && b.type() == null) {
            return new ScoringCommand.RetireBatter(b.batterId(), r.nextBoolean() ? ScoringCommand.RetirementType.HURT : ScoringCommand.RetirementType.OUT);
        }
        return command;
    }
}
