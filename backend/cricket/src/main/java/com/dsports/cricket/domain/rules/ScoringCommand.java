package com.dsports.cricket.domain.rules;

import com.dsports.cricket.domain.model.MatchRules;

import java.util.List;
import java.util.UUID;

/** Everything a scorer can tell the engine. The client sends outcomes only; the engine derives the rest. */
public sealed interface ScoringCommand {

    /** Opens an innings. {@code target} is null in the first innings. */
    record StartInnings(MatchRules rules, int overs, UUID battingTeamId, UUID bowlingTeamId, List<UUID> battingXi, List<UUID> bowlingXi,
                        UUID striker, UUID nonStriker, UUID bowler, Integer target) implements ScoringCommand {
    }

    record Deliver(Delivery delivery) implements ScoringCommand {
    }

    /** Names the batter who comes in (or returns) after a wicket or a retirement. */
    record SelectBatter(UUID batterId) implements ScoringCommand {
    }

    /** Names the bowler of the next over. */
    record SelectBowler(UUID bowlerId) implements ScoringCommand {
    }

    /** The bowler is hurt part-way through an over; the new bowler bowls the rest of it. */
    record ChangeBowlerMidOver(UUID newBowlerId) implements ScoringCommand {
    }

    record RetireBatter(UUID batterId, RetirementType type) implements ScoringCommand {
    }

    /** A manual revised target for the chase (the number is the scorer's; no calculation is done here). */
    record ReviseTarget(int target) implements ScoringCommand {
    }

    record EndInnings(InningsState.EndReason reason) implements ScoringCommand {
    }

    /** HURT is not a wicket and the batter may return. OUT counts as a wicket and the batter cannot return. */
    enum RetirementType { HURT, OUT }
}
