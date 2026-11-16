package com.dsports.ground.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("ground_blackouts")
public class GroundBlackoutEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("ground_id")
    private UUID groundId;

    @Column("start_at")
    private Instant startAt;

    @Column("end_at")
    private Instant endAt;

    @Column("reason")
    private String reason;

    @Column("created_by")
    private UUID createdBy;

    @Column("created_at")
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getGroundId() { return groundId; }
    public void setGroundId(UUID groundId) { this.groundId = groundId; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
