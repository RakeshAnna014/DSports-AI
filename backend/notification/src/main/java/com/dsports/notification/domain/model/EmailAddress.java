package com.dsports.notification.domain.model;

import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;

import java.util.regex.Pattern;

public record EmailAddress(String value) {
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_EMAIL,
                    "Email address must not be empty");
        }
        if (!EMAIL_PATTERN.matcher(value).matches()) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_EMAIL,
                    "Invalid email address format: " + value);
        }
    }

    public static EmailAddress from(String value) {
        return new EmailAddress(value.trim().toLowerCase());
    }
}
