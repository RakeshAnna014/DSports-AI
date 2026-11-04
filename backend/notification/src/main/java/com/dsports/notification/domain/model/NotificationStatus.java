package com.dsports.notification.domain.model;

public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    RETRYING;

    public boolean canTransitionTo(NotificationStatus target) {
        return switch (this) {
            case PENDING -> target == SENT || target == FAILED;
            case SENT -> false;
            case FAILED -> target == RETRYING;
            case RETRYING -> target == SENT || target == FAILED;
        };
    }

    public boolean isTerminal() {
        return this == SENT;
    }
}
