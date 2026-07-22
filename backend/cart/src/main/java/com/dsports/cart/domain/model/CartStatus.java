package com.dsports.cart.domain.model;

public enum CartStatus {
    ACTIVE,
    CHECKED_OUT,
    ABANDONED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isCheckedOut() {
        return this == CHECKED_OUT;
    }

    public boolean isAbandoned() {
        return this == ABANDONED;
    }
}
