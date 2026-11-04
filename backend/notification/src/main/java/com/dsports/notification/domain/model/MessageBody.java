package com.dsports.notification.domain.model;

import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;

public record MessageBody(String value) {
    private static final int MAX_LENGTH = 5000;

    public MessageBody {
        if (value == null || value.trim().isBlank()) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_MESSAGE,
                    "Notification message must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_MESSAGE,
                    "Notification message must not exceed " + MAX_LENGTH + " characters");
        }
    }

    public static MessageBody from(String value) {
        return new MessageBody(value == null ? null : value.trim());
    }
}
