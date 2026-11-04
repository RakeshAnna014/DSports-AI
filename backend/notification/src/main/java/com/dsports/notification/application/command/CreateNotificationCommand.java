package com.dsports.notification.application.command;

import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationType;

import java.util.UUID;

public record CreateNotificationCommand(
    UUID userId,
    NotificationType type,
    NotificationChannel channel,
    String message,
    String subject,
    String referenceId,
    String referenceType
) {}
