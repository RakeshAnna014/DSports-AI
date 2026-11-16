package com.dsports.ground.domain.policy;

import com.dsports.ground.domain.model.Ground;
import com.dsports.ground.domain.model.Slot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Available slots are computed, never stored: the ground's grid for the day, minus slots that overlap an active booking or a
 * blackout, minus slots outside the booking window. Each unavailable slot says why.
 */
public final class SlotCalculator {

    public enum Reason { BOOKED, BLACKED_OUT, TOO_SOON, TOO_FAR }

    public record SlotAvailability(Slot slot, BigDecimal price, boolean available, Reason reason) {
    }

    private SlotCalculator() {
    }

    public static List<SlotAvailability> availability(Ground ground, LocalDate date, Collection<Slot> activeBookings,
                                                      Collection<Slot> blackouts, Instant now, BookingPolicy policy) {
        return ground.slotsOn(date).stream().map(slot -> {
            Reason reason = null;
            if (slot.start().isBefore(now.plus(policy.minLead()))) {
                reason = Reason.TOO_SOON;
            } else if (slot.start().isAfter(now.plus(policy.maxAhead()))) {
                reason = Reason.TOO_FAR;
            } else if (blackouts.stream().anyMatch(slot::overlaps)) {
                reason = Reason.BLACKED_OUT;
            } else if (activeBookings.stream().anyMatch(slot::overlaps)) {
                reason = Reason.BOOKED;
            }
            return new SlotAvailability(slot, ground.slotPrice(slot), reason == null, reason);
        }).toList();
    }
}
