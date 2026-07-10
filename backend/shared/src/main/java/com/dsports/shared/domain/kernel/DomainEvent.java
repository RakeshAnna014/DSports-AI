package com.dsports.shared.domain.kernel;

import java.time.Instant;
import java.util.UUID;

public abstract class DomainEvent {

    private final UUID eventId;
    private final Instant occurredOn;

    protected DomainEvent() {
        this.eventId = UUID.randomUUID();
        this.occurredOn = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getOccurredOn() {
        return occurredOn;
    }
}
