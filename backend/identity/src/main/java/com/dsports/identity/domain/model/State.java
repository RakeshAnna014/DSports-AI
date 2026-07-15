package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public final class State implements ValueObject {

    private static final int MAX_LENGTH = 100;

    private final String value;

    private State(String value) {
        this.value = value;
    }

    public static State from(String name) {
        if (name == null || name.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_STATE,
                    "State must not be null or empty");
        }
        String trimmed = name.strip();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_STATE,
                    "State must not exceed " + MAX_LENGTH + " characters");
        }
        return new State(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State that)) return false;
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
