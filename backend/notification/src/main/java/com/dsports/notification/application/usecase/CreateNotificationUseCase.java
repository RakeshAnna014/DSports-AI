package com.dsports.notification.application.usecase;

import com.dsports.notification.application.command.CreateNotificationCommand;
import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.application.result.NotificationResult;
import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;
import com.dsports.notification.domain.model.MessageBody;
import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

public class CreateNotificationUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateNotificationUseCase.class);
    private static final Set<NotificationChannel> SUPPORTED_CHANNELS = Set.of(
            NotificationChannel.values()
    );

    private final NotificationRepository notificationRepository;
    private final Map<NotificationChannel, NotificationProvider> providers;
    private final EventPublisher eventPublisher;

    public CreateNotificationUseCase(NotificationRepository notificationRepository,
                                      Map<NotificationChannel, NotificationProvider> providers,
                                      EventPublisher eventPublisher) {
        this.notificationRepository = notificationRepository;
        this.providers = providers;
        this.eventPublisher = eventPublisher;
    }

    public Mono<NotificationResult> execute(CreateNotificationCommand command) {
        if (command.userId() == null) {
            return Mono.error(new NotificationDomainException(NotificationErrorCode.USER_NOT_FOUND,
                    "User must exist to create notification"));
        }
        if (command.channel() == null || !SUPPORTED_CHANNELS.contains(command.channel())) {
            return Mono.error(new NotificationDomainException(NotificationErrorCode.UNSUPPORTED_CHANNEL,
                    "Unsupported notification channel: " + command.channel()));
        }

        if (command.referenceId() != null) {
            return notificationRepository.findByUserIdAndTypeAndReferenceId(command.userId(), command.type(), command.referenceId())
                    .map(NotificationResultMapper::toResult)
                    .switchIfEmpty(Mono.defer(() -> createAndSend(command)));
        }
        return createAndSend(command);
    }

    private Mono<NotificationResult> createAndSend(CreateNotificationCommand command) {
        var notification = Notification.createFor(
                NotificationId.generate(),
                command.userId(),
                command.type(),
                command.channel(),
                MessageBody.from(command.message()),
                command.subject(),
                command.referenceId(),
                command.referenceType()
        );

        return notificationRepository.save(notification)
                .then(Mono.defer(() -> sendNotification(notification)))
                .then(Mono.defer(() -> {
                    notification.getDomainEvents().forEach(eventPublisher::publish);
                    notification.clearDomainEvents();
                    log.info("Notification {} created for user {} type {}", notification.getId(), notification.getUserId(), notification.getType());
                    return Mono.just(NotificationResultMapper.toResult(notification));
                }));
    }

    private Mono<Void> sendNotification(Notification notification) {
        var provider = providers.get(notification.getChannel());
        if (provider == null) {
            notification.markFailed("No provider configured for channel: " + notification.getChannel());
            return notificationRepository.save(notification).then();
        }
        return provider.send(notification)
                .flatMap(result -> {
                    if (result.success()) {
                        notification.markSent();
                    } else {
                        notification.markFailed(result.message());
                    }
                    return notificationRepository.save(notification);
                })
                .onErrorResume(e -> {
                    notification.markFailed("Provider error: " + e.getMessage());
                    return notificationRepository.save(notification);
                });
    }
}
