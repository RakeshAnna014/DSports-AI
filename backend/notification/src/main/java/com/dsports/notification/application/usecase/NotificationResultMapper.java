package com.dsports.notification.application.usecase;

import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.domain.model.Notification;

public final class NotificationResultMapper {
    private NotificationResultMapper() {}

    public static NotificationResult toResult(Notification notification) {
        return new NotificationResult(
                notification.getId().value(),
                notification.getUserId(),
                notification.getType(),
                notification.getChannel(),
                notification.getMessage().value(),
                notification.getSubject(),
                notification.getTemplateName(),
                notification.getStatus(),
                notification.getReferenceId(),
                notification.getReferenceType(),
                notification.getFailureReason(),
                notification.getRetryCount(),
                notification.getMaxRetries(),
                notification.getSentAt(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                notification.getVersion()
        );
    }
}
