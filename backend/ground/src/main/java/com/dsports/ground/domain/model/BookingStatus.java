package com.dsports.ground.domain.model;

import java.util.EnumSet;
import java.util.Set;

public enum BookingStatus {
    HELD, CONFIRMED, EXPIRED, CANCELLED, COMPLETED, NO_SHOW;

    /** Only HELD and CONFIRMED bookings occupy their slot (the database overlap constraint uses the same two statuses). */
    public boolean occupiesSlot() {
        return this == HELD || this == CONFIRMED;
    }

    public boolean canTransitionTo(BookingStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<BookingStatus> allowedTargets() {
        return switch (this) {
            case HELD -> EnumSet.of(CONFIRMED, EXPIRED, CANCELLED);
            case CONFIRMED -> EnumSet.of(CANCELLED, COMPLETED, NO_SHOW);
            case EXPIRED, CANCELLED, COMPLETED, NO_SHOW -> EnumSet.noneOf(BookingStatus.class);
        };
    }
}
