package com.dsports.identity.domain.model;

import com.dsports.identity.domain.exception.ErrorCode;
import com.dsports.identity.domain.exception.IdentityDomainException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateOfBirthTest {

    @Test
    void shouldCreateFromValidDate() {
        var dob = DateOfBirth.from(LocalDate.of(2000, 1, 15));
        assertThat(dob.value()).isEqualTo(LocalDate.of(2000, 1, 15));
    }

    @Test
    void shouldRejectFutureDate() {
        assertThatThrownBy(() -> DateOfBirth.from(LocalDate.now().plusDays(1)))
                .isInstanceOf(IdentityDomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_DATE_OF_BIRTH);
    }

    @Test
    void shouldRejectNullDate() {
        assertThatThrownBy(() -> DateOfBirth.from(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectDateTooFarInPast() {
        assertThatThrownBy(() -> DateOfBirth.from(LocalDate.now().minusYears(200)))
                .isInstanceOf(IdentityDomainException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_DATE_OF_BIRTH);
    }

    @Test
    void shouldAcceptTodayAsValid() {
        var dob = DateOfBirth.from(LocalDate.now());
        assertThat(dob.value()).isEqualTo(LocalDate.now());
    }
}
