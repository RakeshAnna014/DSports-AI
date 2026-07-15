package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Country implements ValueObject {

    private static final int MAX_LENGTH = 100;
    private static final Pattern ISO_ALPHA_2 = Pattern.compile("^[A-Z]{2}$");

    private final String value;

    private Country(String value) {
        this.value = value;
    }

    public static Country from(String name) {
        if (name == null || name.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_COUNTRY,
                    "Country must not be null or empty");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_COUNTRY,
                    "Country must not exceed " + MAX_LENGTH + " characters");
        }
        if (!ISO_ALPHA_2.matcher(trimmed).matches()) {
            throw new IdentityDomainException(ErrorCode.INVALID_COUNTRY,
                    "Country must be a valid ISO 3166-1 alpha-2 code (e.g. US, IN)");
        }
        return new Country(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Country that)) return false;
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
