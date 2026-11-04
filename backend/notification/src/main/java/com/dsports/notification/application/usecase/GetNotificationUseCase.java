package com.dsports.notification.application.usecase;

import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.application.query.GetNotificationQuery;
import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.notification.domain.model.NotificationId;
import reactor.core.publisher.Mono;

public class GetNotificationUseCase {
    private final NotificationRepository notificationRepository;

    public GetNotificationUseCase(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Mono<NotificationResult> execute(GetNotificationQuery query) {
        var notificationId = NotificationId.fromUUID(query.notificationId());
        return notificationRepository.findById(notificationId)
                .switchIfEmpty(Mono.error(new NotificationDomainException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification not found: " + query.notificationId())))
                .flatMap(notification -> {
                    if (!query.isAdmin() && !notification.getUserId().equals(query.userId())) {
                        return Mono.error(new NotificationDomainException(
                                NotificationErrorCode.NOTIFICATION_NOT_OWNED_BY_USER,
                                "Notification does not belong to user"));
                    }
                    return Mono.just(NotificationResultMapper.toResult(notification));
                });
    }
}
