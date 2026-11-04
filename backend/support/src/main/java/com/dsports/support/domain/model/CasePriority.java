package com.dsports.support.domain.model;

public enum CasePriority {
    LOW, MEDIUM, HIGH, URGENT;

    public CasePriority bump() {
        return this == URGENT ? URGENT : values()[ordinal() + 1];
    }
}
