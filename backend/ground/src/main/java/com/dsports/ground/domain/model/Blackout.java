package com.dsports.ground.domain.model;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;

import java.time.Instant;
import java.util.UUID;

/** A period during which a whole venue ({@code courtId} null) or one court of it cannot be booked (maintenance, a private event). */
public record Blackout(UUID id, UUID venueId, UUID courtId, Instant startAt, Instant endAt, String reason, UUID createdBy, Instant createdAt) {

    public static Blackout create(UUID venueId, UUID courtId, Instant startAt, Instant endAt, String reason, UUID createdBy, Instant now) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A blackout must end after it starts");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A reason of 1-200 characters is required");
        }
        return new Blackout(UUID.randomUUID(), venueId, courtId, startAt, endAt, reason.trim(), createdBy, now);
    }

    /** True when this blackout closes the given court: it is venue-wide, or it names that court. */
    public boolean appliesTo(UUID court) {
        return courtId == null || courtId.equals(court);
    }

    public Slot period() {
        return new Slot(startAt, endAt);
    }
}
