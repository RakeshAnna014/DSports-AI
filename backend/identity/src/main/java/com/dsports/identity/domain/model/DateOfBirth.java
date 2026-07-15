package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import com.dsports.shared.domain.kernel.ValueObject;

import java.time.LocalDate;
import java.util.Objects;

public final class DateOfBirth implements ValueObject {

    private static final int MAX_AGE_YEARS = 150;

    private final LocalDate value;

    private DateOfBirth(LocalDate value) {
        this.value = value;
    }

    public static DateOfBirth from(LocalDate date) {
        Objects.requireNonNull(date, "dateOfBirth must not be null");
        if (date.isAfter(LocalDate.now())) {
            throw new IdentityDomainException(ErrorCode.INVALID_DATE_OF_BIRTH,
                    "Date of birth must not be in the future");
        }
        if (date.isBefore(LocalDate.now().minusYears(MAX_AGE_YEARS))) {
            throw new IdentityDomainException(ErrorCode.INVALID_DATE_OF_BIRTH,
                    "Date of birth must be within the last " + MAX_AGE_YEARS + " years");
        }
        return new DateOfBirth(date);
    }

    public LocalDate value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DateOfBirth that)) return false;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
