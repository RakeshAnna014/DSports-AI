package com.dsports.ground.domain.model;

import com.dsports.ground.domain.exception.GroundDomainException;
import com.dsports.ground.domain.exception.GroundErrorCode;

import java.time.Instant;
import java.util.UUID;

/** A period during which a ground cannot be booked (maintenance, a private event). */
public record GroundBlackout(UUID id, UUID groundId, Instant startAt, Instant endAt, String reason, UUID createdBy, Instant createdAt) {

    public static GroundBlackout create(UUID groundId, Instant startAt, Instant endAt, String reason, UUID createdBy, Instant now) {
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A blackout must end after it starts");
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new GroundDomainException(GroundErrorCode.VALIDATION_ERROR, "A reason of 1-200 characters is required");
        }
        return new GroundBlackout(UUID.randomUUID(), groundId, startAt, endAt, reason.trim(), createdBy, now);
    }

    public Slot period() {
        return new Slot(startAt, endAt);
    }
}
