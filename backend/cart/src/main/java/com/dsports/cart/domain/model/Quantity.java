package com.dsports.cart.domain.model;

import com.dsports.cart.domain.exception.CartDomainException;
import com.dsports.cart.domain.exception.CartErrorCode;

public record Quantity(int value) {
    public Quantity {
        if (value <= 0) {
            throw new CartDomainException(CartErrorCode.INVALID_QUANTITY, "Quantity must be greater than zero");
        }
        if (value > 99) {
            throw new CartDomainException(CartErrorCode.MAX_QUANTITY_EXCEEDED, "Maximum quantity per item is 99");
        }
    }

    public static Quantity from(int value) {
        return new Quantity(value);
    }

    public Quantity add(Quantity other) {
        int sum = this.value + other.value;
        if (sum > 99) {
            throw new CartDomainException(CartErrorCode.MAX_QUANTITY_EXCEEDED,
                "Combined quantity exceeds maximum of 99");
        }
        return new Quantity(sum);
    }

    public Quantity increment() {
        return new Quantity(this.value + 1);
    }

    public Quantity decrement() {
        return new Quantity(this.value - 1);
    }
}
