package com.dsports.review.domain.model;

import com.dsports.review.domain.event.ReviewApprovedEvent;
import com.dsports.review.domain.event.ReviewCreatedEvent;
import com.dsports.review.domain.event.ReviewUpdatedEvent;
import com.dsports.review.domain.exception.ReviewDomainException;
import com.dsports.review.domain.exception.ReviewErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Review {

    private final ReviewId id;
    private final UUID productId;
    private final UUID orderId;
    private final UUID userId;
    private Rating rating;
    private ReviewComment comment;
    private String title;
    private boolean verifiedPurchase;
    private int helpfulCount;
    private ReviewStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Review(ReviewId id, UUID productId, UUID orderId, UUID userId,
                   Rating rating, ReviewComment comment, String title,
                   boolean verifiedPurchase, int helpfulCount,
                   ReviewStatus status, Instant createdAt, Instant updatedAt,
                   int version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.orderId = orderId;
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.rating = Objects.requireNonNull(rating, "rating must not be null");
        this.comment = comment;
        this.title = title;
        this.verifiedPurchase = verifiedPurchase;
        this.helpfulCount = helpfulCount;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Review create(ReviewId id, UUID productId, UUID orderId, UUID userId,
                                 Rating rating, String title, ReviewComment comment,
                                 boolean verifiedPurchase) {
        var review = new Review(
            id,
            productId,
            orderId,
            userId,
            rating,
            comment,
            title,
            verifiedPurchase,
            0,
            ReviewStatus.PENDING,
            Instant.now(),
            Instant.now(),
            0
        );
        review.recordEvent(new ReviewCreatedEvent(id, productId, userId));
        return review;
    }

    public static Review reconstitute(ReviewId id, UUID productId, UUID orderId, UUID userId,
                                       Rating rating, ReviewComment comment, String title,
                                       boolean verifiedPurchase, int helpfulCount,
                                       ReviewStatus status, Instant createdAt, Instant updatedAt,
                                       int version) {
        return new Review(
            id,
            productId,
            orderId,
            userId,
            rating,
            comment,
            title,
            verifiedPurchase,
            helpfulCount,
            status,
            createdAt,
            updatedAt,
            version
        );
    }

    public void edit(Rating rating, String title, ReviewComment comment) {
        if (this.status != ReviewStatus.APPROVED && this.status != ReviewStatus.PENDING) {
            throw new ReviewDomainException(
                ReviewErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot edit review in status: " + this.status
            );
        }
        this.rating = Objects.requireNonNull(rating, "rating must not be null");
        this.title = title;
        this.comment = comment;
        this.updatedAt = Instant.now();
        recordEvent(new ReviewUpdatedEvent(this.id, this.productId, this.userId));
    }

    public void approve() {
        transitionTo(ReviewStatus.APPROVED);
        this.updatedAt = Instant.now();
        recordEvent(new ReviewApprovedEvent(this.id, this.productId, this.userId));
    }

    public void reject() {
        transitionTo(ReviewStatus.REJECTED);
        this.updatedAt = Instant.now();
    }

    public void hide() {
        transitionTo(ReviewStatus.HIDDEN);
        this.updatedAt = Instant.now();
    }

    public void markHelpful() {
        this.helpfulCount++;
        this.updatedAt = Instant.now();
    }

    private void transitionTo(ReviewStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new ReviewDomainException(
                ReviewErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot transition from " + this.status + " to " + target
            );
        }
        this.status = target;
    }

    public ReviewId getId() { return id; }
    public UUID getProductId() { return productId; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public Rating getRating() { return rating; }
    public ReviewComment getComment() { return comment; }
    public String getTitle() { return title; }
    public boolean isVerifiedPurchase() { return verifiedPurchase; }
    public int getHelpfulCount() { return helpfulCount; }
    public ReviewStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<DomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    private void recordEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Review review)) return false;
        return Objects.equals(id, review.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Review{id=" + id + ", status=" + status + "}";
    }
}
