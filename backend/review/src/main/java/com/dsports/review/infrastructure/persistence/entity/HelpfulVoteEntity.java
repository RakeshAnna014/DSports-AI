package com.dsports.review.infrastructure.persistence.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;
import java.util.UUID;

@Table("review_helpful_votes")
/** Append-only record with an application-assigned ID: the mapper marks it new to force an INSERT. */
public class HelpfulVoteEntity implements Persistable<UUID> {
    @Id private UUID id;
    private UUID reviewId;
    private UUID userId;

    public HelpfulVoteEntity() {}

    @Transient
    private boolean isNew;

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    @Override
    @Transient
    public boolean isNew() { return isNew; }
    public void setNew(boolean isNew) { this.isNew = isNew; }
    public UUID getReviewId() { return reviewId; }
    public void setReviewId(UUID reviewId) { this.reviewId = reviewId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
}
