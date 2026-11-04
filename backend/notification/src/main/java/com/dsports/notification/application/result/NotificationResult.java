package com.dsports.notification.application.result;

import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationStatus;
import com.dsports.notification.domain.model.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResult(
    UUID id,
    UUID userId,
    NotificationType type,
    NotificationChannel channel,
    String message,
    String subject,
    String templateName,
    NotificationStatus status,
    String referenceId,
    String referenceType,
    String failureReason,
    int retryCount,
    int maxRetries,
    Instant sentAt,
    Instant createdAt,
    Instant updatedAt,
    int version
) {}
