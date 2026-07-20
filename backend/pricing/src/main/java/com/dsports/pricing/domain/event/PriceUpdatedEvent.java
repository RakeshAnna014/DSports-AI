package com.dsports.pricing.domain.event;

import com.dsports.pricing.domain.model.PriceId;
import com.dsports.shared.domain.kernel.DomainEvent;

public class PriceUpdatedEvent extends DomainEvent {

    private final PriceId priceId;

    public PriceUpdatedEvent(PriceId priceId) {
        this.priceId = priceId;
    }

    public PriceId getPriceId() {
        return priceId;
    }
}
