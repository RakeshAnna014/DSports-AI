package com.dsports.notification.application.usecase;

import com.dsports.notification.application.command.RetryNotificationCommand;
import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

public class RetryNotificationUseCase {
    private static final Logger log = LoggerFactory.getLogger(RetryNotificationUseCase.class);

    private final NotificationRepository notificationRepository;
    private final Map<NotificationChannel, NotificationProvider> providers;
    private final EventPublisher eventPublisher;

    public RetryNotificationUseCase(NotificationRepository notificationRepository,
                                     Map<NotificationChannel, NotificationProvider> providers,
                                     EventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.providers = providers;
        this.eventPublisher = eventPublisher;
    }

    public Mono<NotificationResult> execute(RetryNotificationCommand command) {
        var notificationId = NotificationId.fromUUID(command.notificationId());
        return notificationRepository.findById(notificationId)
                .switchIfEmpty(Mono.error(new NotificationDomainException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification not found: " + command.notificationId())))
                .flatMap(notification -> {
                    if (!notification.canRetry()) {
                        return Mono.error(new NotificationDomainException(
                                NotificationErrorCode.INVALID_STATUS_TRANSITION,
                                "Notification cannot be retried. Status: " + notification.getStatus()
                                        + ", Retry count: " + notification.getRetryCount()
                                        + "/" + notification.getMaxRetries()));
                    }
                    notification.retry();
                    var provider = providers.get(notification.getChannel());
                    if (provider == null) {
                        notification.markRetryFailed("No provider configured for channel: " + notification.getChannel());
                        return notificationRepository.save(notification)
                                .thenReturn(NotificationResultMapper.toResult(notification));
                    }
                    return provider.retry(notification)
                            .flatMap(result -> {
                                if (result.success()) {
                                    notification.markRetrySent();
                                } else {
                                    notification.markRetryFailed(result.message());
                                }
                                return notificationRepository.save(notification);
                            })
                            .onErrorResume(e -> {
                                notification.markRetryFailed("Provider error: " + e.getMessage());
                                return notificationRepository.save(notification);
                            })
                            .then(Mono.defer(() -> {
                                notification.getDomainEvents().forEach(eventPublisher::publish);
                                notification.clearDomainEvents();
                                log.info("Notification {} retried with status {}", notification.getId(), notification.getStatus());
                                return Mono.just(NotificationResultMapper.toResult(notification));
                            }));
                });
    }
}
