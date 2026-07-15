package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public final class AddressLine implements ValueObject {

    private static final int MAX_LENGTH = 255;

    private final String value;

    private AddressLine(String value) {
        this.value = value;
    }

    public static AddressLine from(String line) {
        if (line == null || line.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_ADDRESS,
                    "Address line must not be null or empty");
        }
        String trimmed = line.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_ADDRESS,
                    "Address line must not exceed " + MAX_LENGTH + " characters");
        }
        return new AddressLine(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AddressLine that)) return false;
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
