package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.BrandId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class BrandArchivedEvent extends DomainEvent {

    private final BrandId brandId;

    public BrandArchivedEvent(BrandId brandId) {
        this.brandId = brandId;
    }

    public BrandId brandId() { return brandId; }
}
