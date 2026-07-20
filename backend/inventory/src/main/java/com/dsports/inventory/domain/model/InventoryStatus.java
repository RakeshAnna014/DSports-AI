package com.dsports.inventory.domain.model;

public enum InventoryStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK;

    public static InventoryStatus fromAvailability(int availableQuantity, int reorderLevel) {
        if (availableQuantity <= 0) {
            return OUT_OF_STOCK;
        }
        if (availableQuantity <= reorderLevel) {
            return LOW_STOCK;
        }
        return IN_STOCK;
    }
}
