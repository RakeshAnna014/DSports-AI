package com.dsports.notification.application.port;

import com.dsports.notification.domain.model.Notification;
import reactor.core.publisher.Mono;

public interface NotificationProvider {
    Mono<ProviderResult> send(Notification notification);
    Mono<ProviderResult> retry(Notification notification);
    Mono<ProviderStatus> getStatus(Notification notification);

    record ProviderResult(boolean success, String message, String providerReference) {}
    record ProviderStatus(String status, String details) {}
}
