package com.dsports.wishlist.domain.model;

import com.dsports.wishlist.domain.exception.WishlistDomainException;
import com.dsports.wishlist.domain.exception.WishlistErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Price(BigDecimal value) {
    public Price {
        if (value == null) {
            throw new WishlistDomainException(WishlistErrorCode.INVALID_PRICE, "Price must not be null");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new WishlistDomainException(WishlistErrorCode.INVALID_PRICE, "Price must not be negative");
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
    }

    public static Price from(BigDecimal value) {
        return new Price(value);
    }

    public static Price zero() {
        return new Price(BigDecimal.ZERO);
    }
}
