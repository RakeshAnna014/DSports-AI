package com.dsports.review.domain.model;

public enum ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    HIDDEN;

    public boolean canTransitionTo(ReviewStatus target) {
        return switch (this) {
            case PENDING -> target == APPROVED || target == REJECTED;
            case APPROVED -> target == HIDDEN;
            case REJECTED -> target == PENDING;
            case HIDDEN -> target == APPROVED;
        };
    }

    public boolean isTerminal() {
        return false;
    }
}
