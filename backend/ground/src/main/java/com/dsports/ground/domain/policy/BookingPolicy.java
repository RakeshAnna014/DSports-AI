package com.dsports.ground.domain.policy;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;

import java.time.Duration;
import java.time.Instant;

/** When and how much can be booked: at least {@code minLead} ahead, at most {@code maxAhead} ahead, 1..{@code maxSlots} consecutive slots. */
public record BookingPolicy(Duration minLead, Duration maxAhead, int maxSlots) {

    public static BookingPolicy defaults() {
        return new BookingPolicy(Duration.ofHours(1), Duration.ofDays(30), 4);
    }

    public void check(Instant start, Instant now, int slotCount) {
        if (slotCount < 1) {
            throw new GroundDomainException(GroundErrorCode.INVALID_SLOT, "The requested time is not on this ground's slot grid");
        }
        if (slotCount > maxSlots) {
            throw new GroundDomainException(GroundErrorCode.INVALID_SLOT, "At most " + maxSlots + " consecutive slots can be booked at once");
        }
        if (start.isBefore(now.plus(minLead))) {
            throw new GroundDomainException(GroundErrorCode.BOOKING_TOO_SOON,
                "Bookings must be made at least " + minLead.toHours() + " hour(s) ahead");
        }
        if (start.isAfter(now.plus(maxAhead))) {
            throw new GroundDomainException(GroundErrorCode.BOOKING_TOO_FAR,
                "Bookings can be made at most " + maxAhead.toDays() + " days ahead");
        }
    }
}
