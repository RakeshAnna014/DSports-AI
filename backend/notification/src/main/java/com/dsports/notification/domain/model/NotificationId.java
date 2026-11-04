package com.dsports.notification.domain.model;

import java.util.Objects;
import java.util.UUID;

public record NotificationId(UUID value) {
    public NotificationId {
        Objects.requireNonNull(value, "NotificationId must not be null");
    }

    public static NotificationId generate() {
        return new NotificationId(UUID.randomUUID());
    }

    public static NotificationId fromUUID(UUID value) {
        return new NotificationId(value);
    }
}
