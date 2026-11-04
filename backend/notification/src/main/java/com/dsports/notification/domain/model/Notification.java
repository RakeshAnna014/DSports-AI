package com.dsports.notification.domain.model;

import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Notification {
    private static final int DEFAULT_MAX_RETRIES = 3;

    private final NotificationId id;
    private final UUID userId;
    private final NotificationType type;
    private final NotificationChannel channel;
    private MessageBody message;
    private String subject;
    private String templateName;
    private NotificationStatus status;
    private String referenceId;
    private String referenceType;
    private String failureReason;
    private int retryCount;
    private int maxRetries;
    private Instant sentAt;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Notification(NotificationId id, UUID userId, NotificationType type, NotificationChannel channel,
                         MessageBody message, String subject, String templateName, NotificationStatus status,
                         String referenceId, String referenceType, String failureReason,
                         int retryCount, int maxRetries, Instant sentAt,
                         Instant createdAt, Instant updatedAt, int version) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.type = Objects.requireNonNull(type);
        this.channel = Objects.requireNonNull(channel);
        this.message = Objects.requireNonNull(message);
        this.subject = subject;
        this.templateName = templateName;
        this.status = Objects.requireNonNull(status);
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.failureReason = failureReason;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
        this.sentAt = sentAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Notification create(NotificationId id, UUID userId, NotificationType type,
                                       NotificationChannel channel, MessageBody message) {
        return create(id, userId, type, channel, message, null, null);
    }

    public static Notification create(NotificationId id, UUID userId, NotificationType type,
                                       NotificationChannel channel, MessageBody message,
                                       String subject, String templateName) {
        var now = Instant.now();
        var notification = new Notification(id, userId, type, channel, message, subject, templateName,
                NotificationStatus.PENDING, null, null, null,
                0, DEFAULT_MAX_RETRIES, null, now, now, 0);
        return notification;
    }

    /** Notification about a business object (order, payment, refund); at most one per user, type and reference. */
    public static Notification createFor(NotificationId id, UUID userId, NotificationType type,
                                         NotificationChannel channel, MessageBody message, String subject,
                                         String referenceId, String referenceType) {
        var now = Instant.now();
        return new Notification(id, userId, type, channel, message, subject, null,
                NotificationStatus.PENDING, referenceId, referenceType, null,
                0, DEFAULT_MAX_RETRIES, null, now, now, 0);
    }

    public static Notification reconstitute(NotificationId id, UUID userId, NotificationType type,
                                              NotificationChannel channel, MessageBody message,
                                              String subject, String templateName, NotificationStatus status,
                                              String referenceId, String referenceType, String failureReason,
                                              int retryCount, int maxRetries, Instant sentAt,
                                              Instant createdAt, Instant updatedAt, int version) {
        return new Notification(id, userId, type, channel, message, subject, templateName, status,
                referenceId, referenceType, failureReason, retryCount, maxRetries, sentAt,
                createdAt, updatedAt, version);
    }

    public void markSent() {
        transitionTo(NotificationStatus.SENT);
        this.sentAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        transitionTo(NotificationStatus.FAILED);
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public void retry() {
        if (status != NotificationStatus.FAILED) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_STATUS_TRANSITION,
                    "Only failed notifications can be retried. Current status: " + status);
        }
        if (retryCount >= maxRetries) {
            throw new NotificationDomainException(NotificationErrorCode.MAX_RETRIES_EXCEEDED,
                    "Notification has exceeded maximum retry limit of " + maxRetries);
        }
        transitionTo(NotificationStatus.RETRYING);
        this.retryCount++;
        this.updatedAt = Instant.now();
    }

    public void markRetrySent() {
        if (status != NotificationStatus.RETRYING) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_STATUS_TRANSITION,
                    "Only retrying notifications can be marked as sent. Current status: " + status);
        }
        transitionTo(NotificationStatus.SENT);
        this.sentAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markRetryFailed(String reason) {
        if (status != NotificationStatus.RETRYING) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_STATUS_TRANSITION,
                    "Only retrying notifications can be marked as failed. Current status: " + status);
        }
        transitionTo(NotificationStatus.FAILED);
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public boolean canRetry() {
        return status == NotificationStatus.FAILED && retryCount < maxRetries;
    }

    private void transitionTo(NotificationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot transition from " + status + " to " + target);
        }
        this.status = target;
    }

    public NotificationId getId() { return id; }
    public UUID getUserId() { return userId; }
    public NotificationType getType() { return type; }
    public NotificationChannel getChannel() { return channel; }
    public MessageBody getMessage() { return message; }
    public String getSubject() { return subject; }
    public String getTemplateName() { return templateName; }
    public NotificationStatus getStatus() { return status; }
    public String getReferenceId() { return referenceId; }
    public String getReferenceType() { return referenceType; }
    public String getFailureReason() { return failureReason; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getSentAt() { return sentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public List<DomainEvent> getDomainEvents() { return List.copyOf(domainEvents); }
    public void clearDomainEvents() { domainEvents.clear(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Notification that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Notification{id=" + id + ", userId=" + userId + ", type=" + type
                + ", channel=" + channel + ", status=" + status + "}";
    }
}
