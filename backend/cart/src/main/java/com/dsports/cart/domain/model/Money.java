package com.dsports.cart.domain.model;

import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal value) {
    public Money {
        if (value == null) {
            throw new CartDomainException(CartErrorCode.INVALID_PRICE, "Price must not be null");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new CartDomainException(CartErrorCode.INVALID_PRICE, "Price must not be negative");
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money from(BigDecimal value) {
        return new Money(value);
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO);
    }

    public Money multiply(int quantity) {
        return new Money(this.value.multiply(BigDecimal.valueOf(quantity)));
    }

    public Money add(Money other) {
        return new Money(this.value.add(other.value));
    }

    public Money subtract(Money other) {
        return new Money(this.value.subtract(other.value));
    }
}
