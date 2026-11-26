package com.dsports.cricket.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("matches")
public class MatchEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("team_a_id")
    private UUID teamAId;

    @Column("team_b_id")
    private UUID teamBId;

    @Column("overs")
    private int overs;

    @Column("ball_type")
    private String ballType;

    @Column("balls_per_over")
    private int ballsPerOver;

    @Column("players_per_side")
    private int playersPerSide;

    @Column("max_overs_per_bowler")
    private Integer maxOversPerBowler;

    @Column("wide_penalty")
    private int widePenalty;

    @Column("no_ball_penalty")
    private int noBallPenalty;

    @Column("free_hit_enabled")
    private boolean freeHitEnabled;

    @Column("last_man_stands")
    private boolean lastManStands;

    @Column("status")
    private String status;

    @Column("scheduled_at")
    private Instant scheduledAt;

    @Column("city")
    private String city;

    @Column("booking_id")
    private UUID bookingId;

    @Column("venue_name")
    private String venueName;

    @Column("umpires")
    private String umpires;

    @Column("toss_winner_team_id")
    private UUID tossWinnerTeamId;

    @Column("toss_decision")
    private String tossDecision;

    @Column("created_by")
    private UUID createdBy;

    @Version
    @Column("version")
    private int version;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTeamAId() { return teamAId; }
    public void setTeamAId(UUID teamAId) { this.teamAId = teamAId; }
    public UUID getTeamBId() { return teamBId; }
    public void setTeamBId(UUID teamBId) { this.teamBId = teamBId; }
    public int getOvers() { return overs; }
    public void setOvers(int overs) { this.overs = overs; }
    public String getBallType() { return ballType; }
    public void setBallType(String ballType) { this.ballType = ballType; }
    public int getBallsPerOver() { return ballsPerOver; }
    public void setBallsPerOver(int ballsPerOver) { this.ballsPerOver = ballsPerOver; }
    public int getPlayersPerSide() { return playersPerSide; }
    public void setPlayersPerSide(int playersPerSide) { this.playersPerSide = playersPerSide; }
    public Integer getMaxOversPerBowler() { return maxOversPerBowler; }
    public void setMaxOversPerBowler(Integer maxOversPerBowler) { this.maxOversPerBowler = maxOversPerBowler; }
    public int getWidePenalty() { return widePenalty; }
    public void setWidePenalty(int widePenalty) { this.widePenalty = widePenalty; }
    public int getNoBallPenalty() { return noBallPenalty; }
    public void setNoBallPenalty(int noBallPenalty) { this.noBallPenalty = noBallPenalty; }
    public boolean isFreeHitEnabled() { return freeHitEnabled; }
    public void setFreeHitEnabled(boolean freeHitEnabled) { this.freeHitEnabled = freeHitEnabled; }
    public boolean isLastManStands() { return lastManStands; }
    public void setLastManStands(boolean lastManStands) { this.lastManStands = lastManStands; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public UUID getBookingId() { return bookingId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }
    public String getUmpires() { return umpires; }
    public void setUmpires(String umpires) { this.umpires = umpires; }
    public UUID getTossWinnerTeamId() { return tossWinnerTeamId; }
    public void setTossWinnerTeamId(UUID tossWinnerTeamId) { this.tossWinnerTeamId = tossWinnerTeamId; }
    public String getTossDecision() { return tossDecision; }
    public void setTossDecision(String tossDecision) { this.tossDecision = tossDecision; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
