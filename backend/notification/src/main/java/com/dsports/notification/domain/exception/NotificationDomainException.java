package com.dsports.notification.domain.exception;

import java.util.Collections;
import java.util.Map;

public class NotificationDomainException extends RuntimeException {
    private final NotificationErrorCode errorCode;
    private final Map<String, Object> context;

    public NotificationDomainException(NotificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = Collections.emptyMap();
    }

    public NotificationDomainException(NotificationErrorCode errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context != null ? Collections.unmodifiableMap(context) : Collections.emptyMap();
    }

    public NotificationErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getContext() {
        return context;
    }
}
