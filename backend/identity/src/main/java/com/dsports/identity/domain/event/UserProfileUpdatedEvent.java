package com.dsports.identity.domain.event;

import com.dsports.identity.domain.model.UserId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.Set;

public final class UserProfileUpdatedEvent extends DomainEvent {

    private final UserId userId;
    private final Instant changedAt;
    private final Set<String> changedFields;

    public UserProfileUpdatedEvent(UserId userId, Instant changedAt, Set<String> changedFields) {
        this.userId = userId;
        this.changedAt = changedAt;
        this.changedFields = changedFields;
    }

    public UserId userId() {
        return userId;
    }

    public Instant changedAt() {
        return changedAt;
    }

    public Set<String> changedFields() {
        return changedFields;
    }
}
