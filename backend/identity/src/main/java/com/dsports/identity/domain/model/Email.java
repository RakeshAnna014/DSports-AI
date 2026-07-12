package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Email implements ValueObject {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final int MAX_LENGTH = 254;

    private final String value;

    private Email(String value) {
        this.value = value;
    }

    public static Email from(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_EMAIL, "Email must not be null or empty");
        }
        String normalized = rawEmail.strip().toLowerCase();
        if (normalized.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_EMAIL,
                    "Email must not exceed " + MAX_LENGTH + " characters");
        }
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IdentityDomainException(ErrorCode.INVALID_EMAIL,
                    "Invalid email format: " + rawEmail);
        }
        return new Email(normalized);
    }

    public String value() {
        return value;
    }

    public String domain() {
        return value.substring(value.indexOf('@') + 1);
    }

    public String localPart() {
        return value.substring(0, value.indexOf('@'));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Email other)) return false;
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
