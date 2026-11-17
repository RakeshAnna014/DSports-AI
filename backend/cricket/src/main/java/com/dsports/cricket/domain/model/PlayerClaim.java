package com.dsports.cricket.domain.model;

import com.dsports.cricket.domain.exception.CricketDomainException;
import com.dsports.cricket.domain.exception.CricketErrorCode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A registered customer's request to be linked to a guest profile. Only the guest's creator or an administrator can decide it. */
public final class PlayerClaim {

    private final UUID id;
    private final UUID playerId;
    private final UUID claimantId;
    private ClaimStatus status;
    private UUID decidedBy;
    private final Instant createdAt;
    private Instant decidedAt;

    private PlayerClaim(UUID id, UUID playerId, UUID claimantId, ClaimStatus status, UUID decidedBy, Instant createdAt, Instant decidedAt) {
        this.id = Objects.requireNonNull(id);
        this.playerId = Objects.requireNonNull(playerId);
        this.claimantId = Objects.requireNonNull(claimantId);
        this.status = status;
        this.decidedBy = decidedBy;
        this.createdAt = createdAt;
        this.decidedAt = decidedAt;
    }

    public static PlayerClaim request(UUID playerId, UUID claimantId, Instant now) {
        return new PlayerClaim(UUID.randomUUID(), playerId, claimantId, ClaimStatus.PENDING, null, now, null);
    }

    public static PlayerClaim reconstitute(UUID id, UUID playerId, UUID claimantId, ClaimStatus status, UUID decidedBy, Instant createdAt, Instant decidedAt) {
        return new PlayerClaim(id, playerId, claimantId, status, decidedBy, createdAt, decidedAt);
    }

    public void approve(UUID by, Instant now) {
        decide(ClaimStatus.APPROVED, by, now);
    }

    public void reject(UUID by, Instant now) {
        decide(ClaimStatus.REJECTED, by, now);
    }

    private void decide(ClaimStatus outcome, UUID by, Instant now) {
        if (status != ClaimStatus.PENDING) {
            throw new CricketDomainException(CricketErrorCode.CLAIM_NOT_PENDING, "This claim has already been decided");
        }
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public boolean isPending() {
        return status == ClaimStatus.PENDING;
    }

    public UUID getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public UUID getClaimantId() { return claimantId; }
    public ClaimStatus getStatus() { return status; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
