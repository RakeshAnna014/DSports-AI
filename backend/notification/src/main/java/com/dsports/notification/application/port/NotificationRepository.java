package com.dsports.notification.application.port;

import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationType;
import com.dsports.notification.domain.model.NotificationId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface NotificationRepository {
    Mono<Notification> findById(NotificationId id);
    Flux<Notification> findByUserId(UUID userId);
    Flux<Notification> findAll(int page, int size);
    Mono<Long> countByUserId(UUID userId);
    Mono<Long> countAll();
    Mono<Long> countUnreadByUserId(UUID userId);
    Mono<Notification> findByUserIdAndTypeAndReferenceId(UUID userId, NotificationType type, String referenceId);
    Mono<Void> save(Notification notification);
}
