package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.Slug;
import com.dsports.catalog.domain.model.SportId;
import com.dsports.catalog.domain.model.SportName;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class SportCreatedEvent extends DomainEvent {

    private final SportId sportId;
    private final SportName name;
    private final Slug slug;

    public SportCreatedEvent(SportId sportId, SportName name, Slug slug) {
        this.sportId = sportId;
        this.name = name;
        this.slug = slug;
    }

    public SportId sportId() { return sportId; }
    public SportName name() { return name; }
    public Slug slug() { return slug; }
}
