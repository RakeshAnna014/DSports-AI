package com.dsports.identity.domain.event;

import com.dsports.identity.domain.model.AddressId;
import com.dsports.identity.domain.model.AddressType;
import com.dsports.identity.domain.model.UserId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;

public final class AddressRemovedEvent extends DomainEvent {

    private final UserId userId;
    private final AddressId addressId;
    private final AddressType type;
    private final Instant removedAt;

    public AddressRemovedEvent(UserId userId, AddressId addressId, AddressType type, Instant removedAt) {
        this.userId = userId;
        this.addressId = addressId;
        this.type = type;
        this.removedAt = removedAt;
    }

    public UserId userId() { return userId; }
    public AddressId addressId() { return addressId; }
    public AddressType type() { return type; }
    public Instant removedAt() { return removedAt; }
}
