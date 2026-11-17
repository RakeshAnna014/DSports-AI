package com.dsports.cricket.interfaces;

import com.dsports.cricket.domain.model.BallType;
import com.dsports.cricket.domain.model.BattingHand;
import com.dsports.cricket.domain.model.Match;
import com.dsports.cricket.domain.model.MatchRules;
import com.dsports.cricket.domain.model.Player;
import com.dsports.cricket.domain.model.PlayingRole;
import com.dsports.cricket.domain.model.TossDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request bodies of the cricket API. Bean validation rejects malformed input early; the domain re-validates every rule it owns. */
public final class CricketRequests {

    private CricketRequests() {
    }

    public record PlayerRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 20) String phone,
        PlayingRole role,
        BattingHand battingHand,
        @Size(max = 40) String bowlingStyle,
        @Size(max = 500) String photoUrl) {

        public Player.Details toDetails() {
            return new Player.Details(name, phone, role, battingHand, bowlingStyle, photoUrl);
        }
    }

    public record UpdatePlayerRequest(@NotNull @Valid PlayerRequest player, @NotNull @Min(0) Integer expectedVersion) {
    }

    public record TeamRequest(@NotBlank @Size(max = 60) String name, @Size(max = 80) String city) {
    }

    public record UpdateTeamRequest(@NotBlank @Size(max = 60) String name, @Size(max = 80) String city, @NotNull @Min(0) Integer expectedVersion) {
    }

    public record PlayerIdRequest(@NotNull UUID playerId) {
    }

    /** Optional rule overrides; anything left out takes the standard value (6 balls, 11 a side, 1-run wides and no-balls, free hit on, bowler limit a fifth of the match). */
    public record RulesRequest(@Min(4) @Max(10) Integer ballsPerOver, @Min(2) @Max(11) Integer playersPerSide, @Min(1) @Max(100) Integer maxOversPerBowler,
                               @Min(0) @Max(5) Integer widePenalty, @Min(0) @Max(5) Integer noBallPenalty, Boolean freeHitEnabled, Boolean lastManStands) {

        MatchRules toRules() {
            var d = MatchRules.standard();
            return new MatchRules(ballsPerOver == null ? d.ballsPerOver() : ballsPerOver, playersPerSide == null ? d.playersPerSide() : playersPerSide, maxOversPerBowler,
                widePenalty == null ? d.widePenalty() : widePenalty, noBallPenalty == null ? d.noBallPenalty() : noBallPenalty,
                freeHitEnabled == null ? d.freeHitEnabled() : freeHitEnabled, lastManStands == null ? d.lastManStands() : lastManStands);
        }
    }

    public record CreateMatchRequest(@NotNull UUID teamAId, @NotNull UUID teamBId, @NotNull @Min(1) @Max(100) Integer overs, @NotNull BallType ballType, @Valid RulesRequest rules,
                                     Instant scheduledAt, @Size(max = 80) String city, @Size(max = 200) String umpires, UUID bookingId) {

        Match.Settings toSettings() {
            return new Match.Settings(overs, ballType, rules == null ? MatchRules.standard() : rules.toRules(), scheduledAt, city, umpires);
        }
    }

    public record UpdateMatchRequest(@NotNull @Min(1) @Max(100) Integer overs, @NotNull BallType ballType, @Valid RulesRequest rules, Instant scheduledAt,
                                     @Size(max = 80) String city, @Size(max = 200) String umpires, @NotNull @Min(0) Integer expectedVersion) {

        Match.Settings toSettings() {
            return new Match.Settings(overs, ballType, rules == null ? MatchRules.standard() : rules.toRules(), scheduledAt, city, umpires);
        }
    }

    public record TossRequest(@NotNull UUID winnerTeamId, @NotNull TossDecision decision) {
    }

    public record PlayingXiRequest(@NotNull UUID teamId, @NotNull @Size(max = 11) List<@NotNull UUID> playerIds) {
    }

    public record ScorerRequest(@NotBlank @Size(max = 254) String email) {
    }
}
