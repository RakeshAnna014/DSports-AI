package com.dsports.pricing.domain.event;

import com.dsports.pricing.domain.model.PriceId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;

public class PriceScheduledEvent extends DomainEvent {

    private final PriceId priceId;
    private final Instant scheduledFrom;

    public PriceScheduledEvent(PriceId priceId, Instant scheduledFrom) {
        this.priceId = priceId;
        this.scheduledFrom = scheduledFrom;
    }

    public PriceId getPriceId() {
        return priceId;
    }

    public Instant getScheduledFrom() {
        return scheduledFrom;
    }
}
