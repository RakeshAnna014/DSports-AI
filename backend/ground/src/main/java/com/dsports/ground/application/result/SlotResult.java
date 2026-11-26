package com.dsports.ground.application.result;

import com.dsports.ground.domain.policy.SlotCalculator;

import java.math.BigDecimal;
import java.time.Instant;

/** One slot of a ground on a day: when, what it costs, whether it can be booked now and, if not, why ({@code BOOKED}, {@code BLACKED_OUT}, {@code TOO_SOON}, {@code TOO_FAR}). */
public record SlotResult(Instant start, Instant end, BigDecimal price, boolean available, String unavailableReason) {

    public static SlotResult from(SlotCalculator.SlotAvailability a) {
        return new SlotResult(a.slot().start(), a.slot().end(), a.price(), a.available(), a.reason() == null ? null : a.reason().name());
    }
}
