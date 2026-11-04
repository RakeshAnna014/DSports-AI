package com.dsports.notification.application.usecase;

import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.application.query.GetNotificationHistoryQuery;
import com.dsports.notification.application.result.NotificationHistoryResult;
import reactor.core.publisher.Mono;

public class NotificationHistoryUseCase {
    private final NotificationRepository notificationRepository;

    public NotificationHistoryUseCase(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    public Mono<NotificationHistoryResult> execute(GetNotificationHistoryQuery query) {
        var page = Math.max(0, query.page());
        var size = Math.max(1, Math.min(100, query.size()));

        Mono<Long> countMono = query.isAdmin()
                ? notificationRepository.countAll()
                : notificationRepository.countByUserId(query.userId());

        var notificationsMono = query.isAdmin()
                ? notificationRepository.findAll(page, size)
                : notificationRepository.findByUserId(query.userId());

        return Mono.zip(notificationsMono.collectList(), countMono)
                .map(tuple -> {
                    var results = tuple.getT1().stream()
                            .map(NotificationResultMapper::toResult)
                            .toList();
                    return new NotificationHistoryResult(results, tuple.getT2(), page, size);
                });
    }
}
