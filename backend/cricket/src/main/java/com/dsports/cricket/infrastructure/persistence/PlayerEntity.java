package com.dsports.cricket.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("players")
public class PlayerEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("name")
    private String name;

    @Column("phone")
    private String phone;

    @Column("playing_role")
    private String playingRole;

    @Column("batting_hand")
    private String battingHand;

    @Column("bowling_style")
    private String bowlingStyle;

    @Column("photo_url")
    private String photoUrl;

    @Column("customer_id")
    private UUID customerId;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPlayingRole() { return playingRole; }
    public void setPlayingRole(String playingRole) { this.playingRole = playingRole; }
    public String getBattingHand() { return battingHand; }
    public void setBattingHand(String battingHand) { this.battingHand = battingHand; }
    public String getBowlingStyle() { return bowlingStyle; }
    public void setBowlingStyle(String bowlingStyle) { this.bowlingStyle = bowlingStyle; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
