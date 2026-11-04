package com.dsports.shipment.domain.model;

import java.util.Objects;
import java.util.UUID;

public record ShipmentId(UUID value) {
    public ShipmentId {
        Objects.requireNonNull(value, "ShipmentId must not be null");
    }

    public static ShipmentId generate() {
        return new ShipmentId(UUID.randomUUID());
    }

    public static ShipmentId fromUUID(UUID uuid) {
        return new ShipmentId(uuid);
    }
}
