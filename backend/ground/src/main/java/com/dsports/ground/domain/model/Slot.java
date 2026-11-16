package com.dsports.ground.domain.model;

import java.time.Instant;

/** A half-open time range [start, end): back-to-back slots never overlap. */
public record Slot(Instant start, Instant end) {

    public Slot {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("A slot must end after it starts");
        }
    }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    public boolean overlaps(Slot other) {
        return overlaps(other.start, other.end);
    }
}
