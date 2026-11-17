package com.dsports.cricket.application.result;

import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.TossDecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A match as the caller sees it. {@code canManage}: the creator (settings, scorers); {@code canScore}: the creator or an assigned scorer (toss, playing XIs).
 * {@code scorers} is shown only to people who can score.
 */
public record MatchResult(UUID id, String status, int version, Side teamA, Side teamB, int overs, BallType ballType, MatchRules rules, Instant scheduledAt, String city,
                          VenueInfo venue, String umpires, Toss toss, UUID battingFirstTeamId, boolean readyToStart, boolean canManage, boolean canScore, List<Scorer> scorers) {

    /** {@code squad}: every member of the team, to pick the XI from; shown only to people who can score. */
    public record Side(UUID teamId, String teamName, List<Selected> playingXi, List<Selected> squad) {
    }

    public record Selected(UUID playerId, String name, int battingOrder) {
    }

    public record VenueInfo(UUID bookingId, String name, String city) {
    }

    public record Toss(UUID winnerTeamId, TossDecision decision) {
    }

    public record Scorer(UUID userId, String name, String email) {
    }
}
