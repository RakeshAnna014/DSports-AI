package com.dsports.notification.application.usecase;

import com.dsports.notification.application.command.RetryNotificationCommand;
import com.dsports.notification.application.port.EventPublisher;
import com.dsports.notification.application.port.NotificationProvider;
import com.dsports.notification.application.port.NotificationRepository;
import com.dsports.notification.domain.model.MessageBody;
import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationId;
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

class RetryNotificationUseCaseTest {

    private NotificationRepository repository;
    private EventPublisher eventPublisher;
    private NotificationProvider provider;
    private RetryNotificationUseCase useCase;
    private NotificationId notificationId;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        eventPublisher = mock(EventPublisher.class);
        provider = mock(NotificationProvider.class);
        useCase = new RetryNotificationUseCase(repository, Map.of(NotificationChannel.EMAIL, provider), eventPublisher);
        notificationId = NotificationId.generate();
    }

    @Test
    void shouldRetryFailedNotification() {
        var notification = Notification.create(
                notificationId, UUID.randomUUID(), NotificationType.ORDER_PLACED,
                NotificationChannel.EMAIL, MessageBody.from("Test"));
        notification.markFailed("Timeout");

        when(repository.findById(notificationId)).thenReturn(Mono.just(notification));
        when(repository.save(any())).thenReturn(Mono.empty());
        when(provider.retry(any())).thenReturn(Mono.just(new NotificationProvider.ProviderResult(true, "Sent", "ref-1")));

        StepVerifier.create(useCase.execute(new RetryNotificationCommand(notificationId.value())))
                .assertNext(result -> {
                    assertEquals(NotificationStatus.SENT, result.status());
                    assertEquals(1, result.retryCount());
                })
                .verifyComplete();
    }

    @Test
    void shouldRejectRetryForSentNotification() {
        var notification = Notification.create(
                notificationId, UUID.randomUUID(), NotificationType.ORDER_PLACED,
                NotificationChannel.EMAIL, MessageBody.from("Test"));
        notification.markSent();

        when(repository.findById(notificationId)).thenReturn(Mono.just(notification));

        StepVerifier.create(useCase.execute(new RetryNotificationCommand(notificationId.value())))
                .expectError()
                .verify();
    }

    @Test
    void shouldRejectRetryForNonExistentNotification() {
        when(repository.findById(notificationId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new RetryNotificationCommand(notificationId.value())))
                .expectError()
                .verify();
    }
}
