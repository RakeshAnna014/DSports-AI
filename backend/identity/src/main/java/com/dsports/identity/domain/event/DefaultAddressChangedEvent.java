package com.dsports.identity.domain.event;

import com.dsports.identity.domain.model.AddressId;
import com.dsports.identity.domain.model.AddressType;
import com.dsports.identity.domain.model.UserId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;

public final class DefaultAddressChangedEvent extends DomainEvent {

    private final UserId userId;
    private final AddressId addressId;
    private final AddressType type;
    private final Instant changedAt;

    public DefaultAddressChangedEvent(UserId userId, AddressId addressId, AddressType type, Instant changedAt) {
        this.userId = userId;
        this.addressId = addressId;
        this.type = type;
        this.changedAt = changedAt;
    }

    public UserId userId() { return userId; }
    public AddressId addressId() { return addressId; }
    public AddressType type() { return type; }
    public Instant changedAt() { return changedAt; }
}
