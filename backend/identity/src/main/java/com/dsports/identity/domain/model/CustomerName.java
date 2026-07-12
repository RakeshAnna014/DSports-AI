package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.util.Objects;

public final class CustomerName implements ValueObject {

    private static final int MAX_LENGTH = 100;
    private static final String DISPLAY_FORMAT = "[A-Za-zÀ-ÿ'\\-., ]+";

    private final String firstName;
    private final String lastName;

    private CustomerName(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public static CustomerName of(String firstName, String lastName) {
        if (firstName == null || firstName.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_CUSTOMER_NAME,
                    "First name must not be null or empty");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new IdentityDomainException(ErrorCode.INVALID_CUSTOMER_NAME,
                    "Last name must not be null or empty");
        }
        String trimmedFirst = firstName.strip();
        String trimmedLast = lastName.strip();
        if (trimmedFirst.length() > MAX_LENGTH || trimmedLast.length() > MAX_LENGTH) {
            throw new IdentityDomainException(ErrorCode.INVALID_CUSTOMER_NAME,
                    "Name must not exceed " + MAX_LENGTH + " characters");
        }
        if (containsInvalidCharacters(trimmedFirst) || containsInvalidCharacters(trimmedLast)) {
            throw new IdentityDomainException(ErrorCode.INVALID_CUSTOMER_NAME,
                    "Name contains invalid characters (HTML/script tags not allowed)");
        }
        return new CustomerName(trimmedFirst, trimmedLast);
    }

    private static boolean containsInvalidCharacters(String value) {
        return value.contains("<") || value.contains(">")
                || value.contains("&") || value.contains("\"");
    }

    public String firstName() {
        return firstName;
    }

    public String lastName() {
        return lastName;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public String displayName() {
        return firstName;
    }

    public String formalName() {
        return lastName + ", " + firstName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomerName that)) return false;
        return Objects.equals(firstName, that.firstName)
                && Objects.equals(lastName, that.lastName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(firstName, lastName);
    }

    @Override
    public String toString() {
        return fullName();
    }
}
