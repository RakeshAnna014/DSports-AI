package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.BrandId;
import com.dsports.catalog.domain.model.BrandName;
import com.dsports.catalog.domain.model.Slug;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class BrandCreatedEvent extends DomainEvent {

    private final BrandId brandId;
    private final BrandName name;
    private final Slug slug;

    public BrandCreatedEvent(BrandId brandId, BrandName name, Slug slug) {
        this.brandId = brandId;
        this.name = name;
        this.slug = slug;
    }

    public BrandId brandId() { return brandId; }
    public BrandName name() { return name; }
    public Slug slug() { return slug; }
}
