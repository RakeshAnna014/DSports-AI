package com.dsports.catalog.domain.model;

public enum Status {
    ACTIVE,
    ARCHIVED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    public boolean isArchived() {
        return this == ARCHIVED;
    }

    public Status transitionTo(Status target) {
        if (this == ARCHIVED) {
            throw new IllegalStateException("Cannot transition from ARCHIVED status");
        }
        return target;
    }
}
