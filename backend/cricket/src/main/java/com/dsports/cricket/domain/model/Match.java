package com.dsports.cricket.domain.model;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dsports.cricket.domain.model.Rules.optionalText;
import static com.dsports.cricket.domain.model.Rules.require;

/**
 * A match between two teams: its settings, the toss, each side's playing XI in batting order, and who may score it. This step covers setup only
 * ({@code SCHEDULED} then {@code TOSS_DONE}); starting and scoring come later. Settings, toss and XIs can change until the match starts.
 */
public final class Match {

    /** Where the match is played, copied from a confirmed venue booking so the match page does not depend on the booking staying around. */
    public record Venue(UUID bookingId, String name, String city) {
    }

    /** Everything that can be edited before the match starts. */
    public record Settings(int overs, BallType ballType, MatchRules rules, Instant scheduledAt, String city, String umpires) {
    }

    private final UUID id;
    private final UUID teamAId;
    private final UUID teamBId;
    private Settings settings;
    private final Venue venue;
    private MatchStatus status;
    private UUID tossWinnerTeamId;
    private TossDecision tossDecision;
    private final List<UUID> xiA;
    private final List<UUID> xiB;
    private final Set<UUID> scorers;
    private final UUID createdBy;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    private Match(UUID id, UUID teamAId, UUID teamBId, Settings settings, Venue venue, MatchStatus status, UUID tossWinnerTeamId, TossDecision tossDecision,
                  List<UUID> xiA, List<UUID> xiB, Set<UUID> scorers, UUID createdBy, int version, Instant createdAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.teamAId = Objects.requireNonNull(teamAId);
        this.teamBId = Objects.requireNonNull(teamBId);
        this.settings = settings;
        this.venue = venue;
        this.status = status;
        this.tossWinnerTeamId = tossWinnerTeamId;
        this.tossDecision = tossDecision;
        this.xiA = new ArrayList<>(xiA);
        this.xiB = new ArrayList<>(xiB);
        this.scorers = new LinkedHashSet<>(scorers);
        this.createdBy = Objects.requireNonNull(createdBy);
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Match create(UUID createdBy, UUID teamAId, UUID teamBId, Settings settings, Venue venue, Instant now) {
        require(teamAId != null && teamBId != null, "Both teams are required");
        if (teamAId.equals(teamBId)) {
            throw new CricketDomainException(CricketErrorCode.INVALID_TEAMS, "A match needs two different teams");
        }
        var checked = check(settings, venue);
        return new Match(UUID.randomUUID(), teamAId, teamBId, checked, venue, MatchStatus.SCHEDULED, null, null, List.of(), List.of(), Set.of(), createdBy, 0, now, now);
    }

    public static Match reconstitute(UUID id, UUID teamAId, UUID teamBId, Settings settings, Venue venue, MatchStatus status, UUID tossWinnerTeamId, TossDecision tossDecision,
                                     List<UUID> xiA, List<UUID> xiB, Set<UUID> scorers, UUID createdBy, int version, Instant createdAt, Instant updatedAt) {
        return new Match(id, teamAId, teamBId, settings, venue, status, tossWinnerTeamId, tossDecision, xiA, xiB, scorers, createdBy, version, createdAt, updatedAt);
    }

    // ---- setup -----------------------------------------------------------------------------------------------

    public void updateSettings(Settings newSettings, Instant now) {
        requireNotStarted();
        var checked = check(newSettings, venue);
        for (var xi : List.of(xiA, xiB)) {
            require(xi.size() <= checked.rules().playersPerSide(), "A playing XI already has more players than the new players-per-side allows");
        }
        this.settings = checked;
        this.updatedAt = now;
    }

    /** Records (or corrects) the toss. The winner must be one of the two teams. */
    public void recordToss(UUID winnerTeamId, TossDecision decision, Instant now) {
        requireNotStarted();
        if (winnerTeamId == null || !(winnerTeamId.equals(teamAId) || winnerTeamId.equals(teamBId))) {
            throw new CricketDomainException(CricketErrorCode.INVALID_TOSS, "The toss winner must be one of the two teams");
        }
        require(decision != null, "The toss decision (bat or bowl) is required");
        this.tossWinnerTeamId = winnerTeamId;
        this.tossDecision = decision;
        this.status = MatchStatus.TOSS_DONE;
        this.updatedAt = now;
    }

    /**
     * Sets one side's playing XI, in batting order. {@code playerIds} must all be members of that team ({@code teamMembers}), without repeats, no more than
     * the match's players per side, and none of them may play for the other side as well.
     */
    public void setPlayingXi(UUID teamId, List<UUID> playerIds, Set<UUID> teamMembers, Instant now) {
        requireNotStarted();
        if (!teamId.equals(teamAId) && !teamId.equals(teamBId)) {
            throw new CricketDomainException(CricketErrorCode.INVALID_TEAMS, "That team is not playing in this match");
        }
        var distinct = new HashSet<>(playerIds);
        if (distinct.size() != playerIds.size()) {
            throw new CricketDomainException(CricketErrorCode.INVALID_PLAYING_XI, "A player cannot be listed twice");
        }
        if (playerIds.size() < 2 || playerIds.size() > settings.rules().playersPerSide()) {
            throw new CricketDomainException(CricketErrorCode.INVALID_PLAYING_XI, "A side needs between 2 and " + settings.rules().playersPerSide() + " players");
        }
        if (!teamMembers.containsAll(playerIds)) {
            throw new CricketDomainException(CricketErrorCode.INVALID_PLAYING_XI, "Every player must be a member of the team");
        }
        var other = teamId.equals(teamAId) ? xiB : xiA;
        if (other.stream().anyMatch(distinct::contains)) {
            throw new CricketDomainException(CricketErrorCode.INVALID_PLAYING_XI, "A player cannot play for both sides");
        }
        var mine = teamId.equals(teamAId) ? xiA : xiB;
        mine.clear();
        mine.addAll(playerIds);
        this.updatedAt = now;
    }

    public void addScorer(UUID userId, Instant now) {
        if (userId.equals(createdBy) || scorers.contains(userId)) {
            throw new CricketDomainException(CricketErrorCode.SCORER_ALREADY_ADDED, "That person can already score this match");
        }
        scorers.add(userId);
        this.updatedAt = now;
    }

    public void removeScorer(UUID userId, Instant now) {
        if (!scorers.remove(userId)) {
            throw new CricketDomainException(CricketErrorCode.NOT_A_SCORER, "That person is not an assigned scorer");
        }
        this.updatedAt = now;
    }

    // ---- queries ---------------------------------------------------------------------------------------------

    public boolean isScorer(UUID user) {
        return createdBy.equals(user) || scorers.contains(user);
    }

    public List<UUID> playingXi(UUID teamId) {
        return List.copyOf(teamId.equals(teamAId) ? xiA : xiB);
    }

    /** The team that bats first, once the toss is done: the winner if they chose to bat, otherwise the other team. */
    public UUID battingFirstTeamId() {
        if (tossWinnerTeamId == null) {
            return null;
        }
        return tossDecision == TossDecision.BAT ? tossWinnerTeamId : (tossWinnerTeamId.equals(teamAId) ? teamBId : teamAId);
    }

    /** True when the toss is done and both sides have a playing XI, so scoring can begin. */
    public boolean readyToStart() {
        return status == MatchStatus.TOSS_DONE && xiA.size() >= 2 && xiB.size() >= 2;
    }

    private void requireNotStarted() {
        if (status != MatchStatus.SCHEDULED && status != MatchStatus.TOSS_DONE) {
            throw new CricketDomainException(CricketErrorCode.MATCH_STARTED, "The match has started, so its setup can no longer change");
        }
    }

    private static Settings check(Settings s, Venue venue) {
        require(s != null, "Match settings are required");
        require(s.overs() >= 1 && s.overs() <= 100, "A match has between 1 and 100 overs");
        require(s.ballType() != null, "The ball type is required");
        var rules = s.rules() == null ? MatchRules.standard() : s.rules();
        rules.validate(s.overs());
        var city = optionalText(s.city(), 80, "City");
        if (city == null && venue != null) {
            city = venue.city();
        }
        return new Settings(s.overs(), s.ballType(), rules, s.scheduledAt(), city, optionalText(s.umpires(), 200, "Umpires"));
    }

    public UUID getId() { return id; }
    public UUID getTeamAId() { return teamAId; }
    public UUID getTeamBId() { return teamBId; }
    public Settings getSettings() { return settings; }
    public Venue getVenue() { return venue; }
    public MatchStatus getStatus() { return status; }
    public UUID getTossWinnerTeamId() { return tossWinnerTeamId; }
    public TossDecision getTossDecision() { return tossDecision; }
    public Set<UUID> getScorers() { return Set.copyOf(scorers); }
    public UUID getCreatedBy() { return createdBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
