package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class PhoneNumber implements ValueObject {

    private static final Pattern E164_PATTERN =
            Pattern.compile("^\\+[1-9]\\d{1,14}$");
    private static final Pattern INDIA_MOBILE_PATTERN =
            Pattern.compile("^\\+91[6-9]\\d{9}$");

    private final String value;

    private PhoneNumber(String value) {
        this.value = value;
    }

    public static PhoneNumber from(String rawNumber) {
        if (rawNumber == null || rawNumber.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_PHONE_NUMBER,
                    "Phone number must not be null or empty");
        }
        String cleaned = rawNumber.strip().replaceAll("[\\s\\-()]", "");
        if (!E164_PATTERN.matcher(cleaned).matches()) {
            throw new IdentityDomainException(ErrorCode.INVALID_PHONE_NUMBER,
                    "Phone number must be in E.164 format (e.g. +919876543210)");
        }
        return new PhoneNumber(cleaned);
    }

    public static PhoneNumber indianMobile(String rawNumber) {
        if (rawNumber == null || rawNumber.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_PHONE_NUMBER,
                    "Phone number must not be null or empty");
        }
        String cleaned = rawNumber.strip().replaceAll("[\\s\\-()]", "");
        if (!INDIA_MOBILE_PATTERN.matcher(cleaned).matches()) {
            throw new IdentityDomainException(ErrorCode.INVALID_PHONE_NUMBER,
                    "Indian mobile number must start with +91 followed by a valid 10-digit number");
        }
        return new PhoneNumber(cleaned);
    }

    public String value() {
        return value;
    }

    public String countryCode() {
        return "+" + value.substring(1, value.length() - 10);
    }

    public String nationalNumber() {
        return value.substring(value.length() - 10);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhoneNumber other)) return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
