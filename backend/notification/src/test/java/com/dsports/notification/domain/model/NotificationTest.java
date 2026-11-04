package com.dsports.notification.domain.model;

import com.dsports.notification.domain.exception.NotificationDomainException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void shouldCreateNotification() {
        var notification = Notification.create(
                NotificationId.generate(), userId, NotificationType.ORDER_PLACED,
                NotificationChannel.IN_APP, MessageBody.from("Test message"));

        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals(NotificationType.ORDER_PLACED, notification.getType());
        assertEquals(NotificationChannel.IN_APP, notification.getChannel());
        assertEquals(userId, notification.getUserId());
        assertEquals(0, notification.getRetryCount());
        assertEquals(3, notification.getMaxRetries());
        assertNull(notification.getSentAt());
    }

    @Test
    void shouldCreateNotificationWithSubjectAndTemplate() {
        var notification = Notification.create(
                NotificationId.generate(), userId, NotificationType.PAYMENT_SUCCESS,
                NotificationChannel.EMAIL, MessageBody.from("Payment OK"), "Subject", "template-1");

        assertEquals("Subject", notification.getSubject());
        assertEquals("template-1", notification.getTemplateName());
    }

    @Test
    void shouldMarkAsSent() {
        var notification = createPendingNotification();
        notification.markSent();
        assertEquals(NotificationStatus.SENT, notification.getStatus());
        assertNotNull(notification.getSentAt());
    }

    @Test
    void shouldMarkAsFailed() {
        var notification = createPendingNotification();
        notification.markFailed("Gateway error");
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals("Gateway error", notification.getFailureReason());
    }

    @Test
    void shouldRetryFailedNotification() {
        var notification = createPendingNotification();
        notification.markFailed("Timeout");
        notification.retry();
        assertEquals(NotificationStatus.RETRYING, notification.getStatus());
        assertEquals(1, notification.getRetryCount());
    }

    @Test
    void shouldRejectRetryOnSentNotification() {
        var notification = createPendingNotification();
        notification.markSent();
        assertThrows(NotificationDomainException.class, notification::retry);
    }

    @Test
    void shouldRejectRetryWhenMaxRetriesExceeded() {
        var notification = createPendingNotification();
        notification.markFailed("Error");
        for (int i = 0; i < 3; i++) {
            notification.retry();
            notification.markRetryFailed("Error " + i);
        }
        assertThrows(NotificationDomainException.class, notification::retry);
    }

    @Test
    void shouldSupportRetrySentTransition() {
        var notification = createPendingNotification();
        notification.markFailed("Error");
        notification.retry();
        notification.markRetrySent();
        assertEquals(NotificationStatus.SENT, notification.getStatus());
    }

    @Test
    void shouldSupportRetryFailedTransition() {
        var notification = createPendingNotification();
        notification.markFailed("Error");
        notification.retry();
        notification.markRetryFailed("Still failing");
        assertEquals(NotificationStatus.FAILED, notification.getStatus());
        assertEquals("Still failing", notification.getFailureReason());
    }

    @Test
    void shouldCheckCanRetry() {
        var notification = createPendingNotification();
        assertFalse(notification.canRetry());
        notification.markFailed("Error");
        assertTrue(notification.canRetry());
        notification.retry();
        assertFalse(notification.canRetry());
        notification.markRetryFailed("Again");
        assertTrue(notification.canRetry());
    }

    @Test
    void shouldRejectInvalidStatusTransition() {
        var notification = createPendingNotification();
        assertThrows(NotificationDomainException.class, () -> notification.markRetrySent());
        assertThrows(NotificationDomainException.class, () -> notification.markRetryFailed("fail"));
    }

    @Test
    void shouldRejectEmptyMessage() {
        assertThrows(NotificationDomainException.class,
                () -> MessageBody.from(""));
        assertThrows(NotificationDomainException.class,
                () -> MessageBody.from("   "));
        assertThrows(NotificationDomainException.class,
                () -> MessageBody.from(null));
    }

    @Test
    void shouldRejectMessageTooLong() {
        var longMsg = "x".repeat(5001);
        assertThrows(NotificationDomainException.class,
                () -> MessageBody.from(longMsg));
    }

    @Test
    void shouldAcceptValidMessage() {
        var msg = MessageBody.from("Valid message");
        assertEquals("Valid message", msg.value());
    }

    private Notification createPendingNotification() {
        return Notification.create(
                NotificationId.generate(), userId, NotificationType.ORDER_PLACED,
                NotificationChannel.IN_APP, MessageBody.from("Test"));
    }
}
