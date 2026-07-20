package com.dsports.inventory.domain.model;

import java.util.UUID;

public record InventoryId(UUID value) {
    public InventoryId {
        if (value == null) {
            throw new IllegalArgumentException("InventoryId must not be null");
        }
    }

    public static InventoryId generate() {
        return new InventoryId(UUID.randomUUID());
    }

    public static InventoryId fromUUID(UUID value) {
        return new InventoryId(value);
    }
}
