package com.dsports.catalog.domain.event;

import com.dsports.catalog.domain.model.SportId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class SportArchivedEvent extends DomainEvent {

    private final SportId sportId;

    public SportArchivedEvent(SportId sportId) {
        this.sportId = sportId;
    }

    public SportId sportId() { return sportId; }
}
