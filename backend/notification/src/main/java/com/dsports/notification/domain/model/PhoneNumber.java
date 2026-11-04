package com.dsports.notification.domain.model;

import com.dsports.notification.domain.exception.NotificationDomainException;
import com.dsports.notification.domain.exception.NotificationErrorCode;

import java.util.regex.Pattern;

public record PhoneNumber(String value) {
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\+?[1-9]\\d{6,14}$");

    public PhoneNumber {
        if (value == null || value.isBlank()) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_PHONE,
                    "Phone number must not be empty");
        }
        if (!PHONE_PATTERN.matcher(value).matches()) {
            throw new NotificationDomainException(NotificationErrorCode.INVALID_PHONE,
                    "Invalid phone number format: " + value);
        }
    }

    public static PhoneNumber from(String value) {
        return new PhoneNumber(value.trim());
    }
}
