package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class PostalCode implements ValueObject {

    private static final int MAX_LENGTH = 20;
    private static final Pattern ALPHANUMERIC = Pattern.compile("^[a-zA-Z0-9]+$");

    private final String value;

    private PostalCode(String value) {
        this.value = value;
    }

    public static PostalCode from(String code) {
        if (code == null || code.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_POSTAL_CODE,
                    "Postal code must not be null or empty");
        }
        String trimmed = code.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_POSTAL_CODE,
                    "Postal code must not exceed " + MAX_LENGTH + " characters");
        }
        if (!ALPHANUMERIC.matcher(trimmed).matches()) {
            throw new IdentityDomainException(ErrorCode.INVALID_POSTAL_CODE,
                    "Postal code must be alphanumeric");
        }
        return new PostalCode(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PostalCode that)) return false;
        return Objects.equals(value, that.value);
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
