package com.dsports.notification.infrastructure.provider;

import com.dsports.notification.domain.model.MessageBody;
import com.dsports.notification.domain.model.Notification;
import com.dsports.notification.domain.model.NotificationChannel;
import com.dsports.notification.domain.model.NotificationId;
import com.dsports.notification.domain.model.NotificationType;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MockSmsProviderTest {

    private final MockSmsProvider provider = new MockSmsProvider();

    @Test
    void shouldSendSuccessfully() {
        var notification = createNotification();
        StepVerifier.create(provider.send(notification))
                .assertNext(result -> {
                    assertTrue(result.success());
                    assertTrue(result.message().contains("SMS sent"));
                })
                .verifyComplete();
    }

    @Test
    void shouldRetrySuccessfully() {
        var notification = createNotification();
        StepVerifier.create(provider.retry(notification))
                .assertNext(result -> assertTrue(result.success()))
                .verifyComplete();
    }

    @Test
    void shouldReturnStatus() {
        var notification = createNotification();
        StepVerifier.create(provider.getStatus(notification))
                .assertNext(status -> assertEquals("DELIVERED", status.status()))
                .verifyComplete();
    }

    private Notification createNotification() {
        return Notification.create(
                NotificationId.generate(), UUID.randomUUID(), NotificationType.ORDER_PLACED,
                NotificationChannel.SMS, MessageBody.from("Test SMS"));
    }
}
