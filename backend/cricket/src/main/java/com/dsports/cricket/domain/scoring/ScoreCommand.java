package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.rules.ScoringCommand;

import java.util.UUID;

/**
 * What a scorer can ask for at match level. The engine's own commands travel inside {@link Play}; starting an innings carries only the openers and the
 * bowler (the teams, XIs, rules, overs and target come from the match); ending the match is decided here, not by the innings engine.
 */
public sealed interface ScoreCommand {

    /** The kind of a command, as stored in the event log. */
    enum Type { START_INNINGS, DELIVER, SELECT_BATTER, SELECT_BOWLER, CHANGE_BOWLER, RETIRE_BATTER, REVISE_TARGET, END_INNINGS, END_MATCH }

    record StartInnings(UUID striker, UUID nonStriker, UUID bowler) implements ScoreCommand {
    }

    /** Any innings command except starting one. */
    record Play(ScoringCommand command) implements ScoreCommand {
        public Play {
            if (command == null || command instanceof ScoringCommand.StartInnings) {
                throw new IllegalArgumentException("Play carries an innings command other than StartInnings");
            }
        }
    }

    record EndMatch(Reason reason) implements ScoreCommand {
        public enum Reason { ABANDONED, NO_RESULT }
    }

    default Type type() {
        return switch (this) {
            case StartInnings c -> Type.START_INNINGS;
            case EndMatch c -> Type.END_MATCH;
            case Play p -> switch (p.command()) {
                case ScoringCommand.Deliver c -> Type.DELIVER;
                case ScoringCommand.SelectBatter c -> Type.SELECT_BATTER;
                case ScoringCommand.SelectBowler c -> Type.SELECT_BOWLER;
                case ScoringCommand.ChangeBowlerMidOver c -> Type.CHANGE_BOWLER;
                case ScoringCommand.RetireBatter c -> Type.RETIRE_BATTER;
                case ScoringCommand.ReviseTarget c -> Type.REVISE_TARGET;
                case ScoringCommand.EndInnings c -> Type.END_INNINGS;
                case ScoringCommand.StartInnings c -> throw new IllegalStateException("Play never carries StartInnings");
            };
        };
    }

    /** True for the choices that follow a ball (the new batter, the new bowler): "undo ball" takes them off together with the ball they followed. */
    default boolean isFollower() {
        return type() == Type.SELECT_BATTER || type() == Type.SELECT_BOWLER;
    }
}
