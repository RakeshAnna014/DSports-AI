package com.dsports.notification.infrastructure.persistence.mapper;

import com.dsports.notification.domain.model.MessageBody;
import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationId;
import com.dsports.notification.domain.model.NotificationStatus;
import com.dsports.notification.domain.model.NotificationType;
import com.dsports.notification.infrastructure.persistence.entity.NotificationEntity;

public final class NotificationEntityMapper {
    private NotificationEntityMapper() {}

    public static NotificationEntity toEntity(Notification notification) {
        var entity = new NotificationEntity();
        entity.setId(notification.getId().value());
        entity.setUserId(notification.getUserId());
        entity.setType(notification.getType().name());
        entity.setChannel(notification.getChannel().name());
        entity.setSubject(notification.getSubject());
        entity.setMessage(notification.getMessage().value());
        entity.setTemplateName(notification.getTemplateName());
        entity.setStatus(notification.getStatus().name());
        entity.setReferenceId(notification.getReferenceId());
        entity.setReferenceType(notification.getReferenceType());
        entity.setFailureReason(notification.getFailureReason());
        entity.setRetryCount(notification.getRetryCount());
        entity.setMaxRetries(notification.getMaxRetries());
        entity.setSentAt(notification.getSentAt());
        entity.setCreatedAt(notification.getCreatedAt());
        entity.setUpdatedAt(notification.getUpdatedAt());
        entity.setVersion(notification.getVersion());
        return entity;
    }

    public static Notification toDomain(NotificationEntity entity) {
        return Notification.reconstitute(
                NotificationId.fromUUID(entity.getId()),
                entity.getUserId(),
                NotificationType.valueOf(entity.getType()),
                NotificationChannel.valueOf(entity.getChannel()),
                MessageBody.from(entity.getMessage()),
                entity.getSubject(),
                entity.getTemplateName(),
                NotificationStatus.valueOf(entity.getStatus()),
                entity.getReferenceId(),
                entity.getReferenceType(),
                entity.getFailureReason(),
                entity.getRetryCount(),
                entity.getMaxRetries(),
                entity.getSentAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
