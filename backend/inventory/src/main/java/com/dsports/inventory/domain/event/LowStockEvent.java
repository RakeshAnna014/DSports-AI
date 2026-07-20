package com.dsports.inventory.domain.event;

import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class LowStockEvent extends DomainEvent {
    private final InventoryId inventoryId;
    private final int availableQuantity;
    private final int reorderLevel;

    public LowStockEvent(InventoryId inventoryId, int availableQuantity, int reorderLevel) {
        this.inventoryId = inventoryId;
        this.availableQuantity = availableQuantity;
        this.reorderLevel = reorderLevel;
    }

    public InventoryId inventoryId() { return inventoryId; }
    public int availableQuantity() { return availableQuantity; }
    public int reorderLevel() { return reorderLevel; }
}
