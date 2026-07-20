package com.dsports.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

public record WarehouseId(UUID value) {
    public WarehouseId {
        Objects.requireNonNull(value, "WarehouseId must not be null");
    }

    public static WarehouseId generate() {
        return new WarehouseId(UUID.randomUUID());
    }

    public static WarehouseId fromUUID(UUID value) {
        return new WarehouseId(value);
    }
}
