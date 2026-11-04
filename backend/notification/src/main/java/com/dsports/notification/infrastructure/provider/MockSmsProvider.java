package com.dsports.notification.infrastructure.provider;

import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class MockSmsProvider implements NotificationProvider {
    private static final Logger log = LoggerFactory.getLogger(MockSmsProvider.class);

    @Override
    public Mono<ProviderResult> send(Notification notification) {
        log.info("[MOCK SMS] Sending SMS notification {} to user {}: '{}'",
                notification.getId(), notification.getUserId(),
                notification.getMessage().value());
        return simulateSend();
    }

    @Override
    public Mono<ProviderResult> retry(Notification notification) {
        log.info("[MOCK SMS] Retrying SMS notification {} for user {} (attempt {}/{})",
                notification.getId(), notification.getUserId(),
                notification.getRetryCount(), notification.getMaxRetries());
        return simulateSend();
    }

    @Override
    public Mono<ProviderStatus> getStatus(Notification notification) {
        return Mono.just(new ProviderStatus("DELIVERED", "SMS delivered successfully"));
    }

    private Mono<ProviderResult> simulateSend() {
        return Mono.just(new ProviderResult(true, "SMS sent successfully",
                "mock-sms-ref-" + UUID.randomUUID().toString().substring(0, 8)));
    }
}
