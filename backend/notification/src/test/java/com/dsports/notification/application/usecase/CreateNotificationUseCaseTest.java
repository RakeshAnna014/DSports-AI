package com.dsports.notification.application.usecase;

import com.dsports.notification.application.command.CreateNotificationCommand;
import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationStatus;
import com.dsports.notification.domain.model.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateNotificationUseCaseTest {

    private NotificationRepository repository;
    private EventPublisher eventPublisher;
    private NotificationProvider provider;
    private CreateNotificationUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        eventPublisher = mock(EventPublisher.class);
        provider = mock(NotificationProvider.class);
        useCase = new CreateNotificationUseCase(repository, Map.of(NotificationChannel.EMAIL, provider), eventPublisher);
    }

    @Test
    void shouldCreateAndSendNotification() {
        var command = new CreateNotificationCommand(
                UUID.randomUUID(), NotificationType.ORDER_PLACED,
                NotificationChannel.EMAIL, "Test message", "Subject", null, null);

        when(repository.save(any())).thenReturn(Mono.empty());
        when(provider.send(any())).thenReturn(Mono.just(new NotificationProvider.ProviderResult(true, "Sent", "ref-1")));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertEquals("Test message", result.message());
                    assertEquals(NotificationStatus.SENT, result.status());
                })
                .verifyComplete();
    }

    @Test
    void shouldCreateAndMarkFailedWhenProviderFails() {
        var command = new CreateNotificationCommand(
                UUID.randomUUID(), NotificationType.ORDER_PLACED,
                NotificationChannel.EMAIL, "Test message", "Subject", null, null);

        when(repository.save(any())).thenReturn(Mono.empty());
        when(provider.send(any())).thenReturn(Mono.just(new NotificationProvider.ProviderResult(false, "Provider error", null)));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertEquals(NotificationStatus.FAILED, result.status());
                    assertEquals("Provider error", result.failureReason());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectNullUserId() {
        var command = new CreateNotificationCommand(
                null, NotificationType.ORDER_PLACED,
                NotificationChannel.EMAIL, "Test", "Subject", null, null);

        StepVerifier.create(useCase.execute(command))
                .expectError()
                .verify();
    }

    @Test
    void shouldRejectUnsupportedChannel() {
        var command = new CreateNotificationCommand(
                UUID.randomUUID(), NotificationType.ORDER_PLACED,
                null, "Test", "Subject", null, null);

        StepVerifier.create(useCase.execute(command))
                .expectError()
                .verify();
    }
}
