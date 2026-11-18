package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.MatchStatus;
import com.dsports.cricket.domain.rules.InningsState;
import com.dsports.cricket.domain.rules.Outcome;
import com.dsports.cricket.domain.rules.RuleViolation;
import com.dsports.cricket.domain.rules.ScoringCommand;
import com.dsports.cricket.domain.rules.ScoringEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Match-level scoring as a pure function. {@code apply} works out the innings command (the teams, XIs, rules and target of an innings come from the
 * {@link MatchSetup}), runs the 2c engine on it and returns the new {@link MatchScore}. {@code fold} rebuilds a score from a list of commands.
 */
public final class MatchScoring {

    private MatchScoring() {
    }

    public static ScoreOutcome apply(MatchSetup setup, MatchScore score, ScoreCommand command) {
        if (score.endedBy() != null) {
            return reject(RuleViolation.MATCH_ENDED, "The match has been ended");
        }
        return switch (command) {
            case ScoreCommand.StartInnings c -> start(setup, score, c);
            case ScoreCommand.Play p -> play(score, p);
            case ScoreCommand.EndMatch c -> end(score, c);
        };
    }

    /** Rebuilds a score by applying the commands in order. Every command must have been accepted when it was first applied. */
    public static MatchScore fold(MatchSetup setup, List<? extends ScoreCommand> commands) {
        var score = MatchScore.empty();
        for (var command : commands) {
            if (apply(setup, score, command) instanceof ScoreOutcome.Accepted accepted) {
                score = accepted.score();
            } else {
                throw new IllegalStateException("A logged command is no longer accepted: " + command);
            }
        }
        return score;
    }

    private static ScoreOutcome start(MatchSetup setup, MatchScore score, ScoreCommand.StartInnings c) {
        var innings = score.innings();
        if (innings.size() >= 2) {
            return reject(RuleViolation.ALL_INNINGS_PLAYED, "Both innings have been played");
        }
        if (setup.battingFirstTeamId() == null || setup.xiA().size() < 2 || setup.xiB().size() < 2) {
            return reject(RuleViolation.INVALID_SETUP, "The toss and both playing XIs are needed before scoring");
        }
        UUID batting;
        Integer target = null;
        if (innings.isEmpty()) {
            batting = setup.battingFirstTeamId();
        } else {
            var first = innings.get(0);
            if (!first.isComplete()) {
                return reject(RuleViolation.PREVIOUS_INNINGS_NOT_COMPLETE, "The first innings is not finished");
            }
            batting = setup.otherTeam(first.battingTeamId());
            target = first.runs() + 1;
        }
        var bowling = setup.otherTeam(batting);
        var engine = new ScoringCommand.StartInnings(setup.rules(), setup.overs(), batting, bowling, setup.xi(batting), setup.xi(bowling), c.striker(), c.nonStriker(), c.bowler(), target);
        return switch (ScoringEngine.apply(InningsState.notStarted(), engine)) {
            case Outcome.Accepted a -> {
                var all = new ArrayList<>(innings);
                all.add(a.state());
                yield new ScoreOutcome.Accepted(score.withInnings(all), a.waivers());
            }
            case Outcome.Rejected r -> reject(r.violation(), r.message());
        };
    }

    private static ScoreOutcome play(MatchScore score, ScoreCommand.Play play) {
        var current = score.current();
        if (current == null) {
            return reject(RuleViolation.INNINGS_NOT_STARTED, "The innings has not started");
        }
        return switch (ScoringEngine.apply(current, play.command())) {
            case Outcome.Accepted a -> new ScoreOutcome.Accepted(score.withLastInnings(a.state()), a.waivers());
            case Outcome.Rejected r -> reject(r.violation(), r.message());
        };
    }

    private static ScoreOutcome end(MatchScore score, ScoreCommand.EndMatch c) {
        if (score.status() == MatchStatus.COMPLETED) {
            return reject(RuleViolation.MATCH_COMPLETE, "The match is complete");
        }
        if (c.reason() == null) {
            return reject(RuleViolation.INVALID_SETUP, "Say whether the match was abandoned or has no result");
        }
        return new ScoreOutcome.Accepted(score.ended(c.reason()), List.of());
    }

    private static ScoreOutcome.Rejected reject(RuleViolation violation, String message) {
        return new ScoreOutcome.Rejected(violation, message);
    }
}
