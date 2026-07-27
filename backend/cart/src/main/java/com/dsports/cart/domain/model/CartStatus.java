package com.dsports.cart.domain.model;

public enum CartStatus {
    ACTIVE,
    CHECKED_OUT,
    COMPLETED,
    ABANDONED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isCheckedOut() {
        return this == CHECKED_OUT;
    }

    public boolean isCompleted() {
        return this == COMPLETED;
    }

    public boolean isAbandoned() {
        return this == ABANDONED;
    }
}
