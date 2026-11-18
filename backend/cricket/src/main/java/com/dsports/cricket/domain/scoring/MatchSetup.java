package com.dsports.cricket.domain.scoring;

import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;

import java.util.List;
import java.util.UUID;

/** What the match fixes before a ball is bowled: the overs, the rules, the two teams, who bats first and the two playing XIs (in batting order). */
public record MatchSetup(int overs, MatchRules rules, UUID teamAId, UUID teamBId, UUID battingFirstTeamId, List<UUID> xiA, List<UUID> xiB) {

    public MatchSetup {
        xiA = List.copyOf(xiA);
        xiB = List.copyOf(xiB);
    }

    /** The setup of a match whose toss is done and whose XIs are chosen. */
    public static MatchSetup from(Match match) {
        return new MatchSetup(match.getSettings().overs(), match.getSettings().rules(), match.getTeamAId(), match.getTeamBId(), match.battingFirstTeamId(),
            match.playingXi(match.getTeamAId()), match.playingXi(match.getTeamBId()));
    }

    public UUID otherTeam(UUID teamId) {
        return teamId.equals(teamAId) ? teamBId : teamAId;
    }

    public List<UUID> xi(UUID teamId) {
        return teamId.equals(teamAId) ? xiA : xiB;
    }
}
