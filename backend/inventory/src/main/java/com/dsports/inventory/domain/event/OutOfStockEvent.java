package com.dsports.inventory.domain.event;

import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class OutOfStockEvent extends DomainEvent {
    private final InventoryId inventoryId;

    public OutOfStockEvent(InventoryId inventoryId) {
        this.inventoryId = inventoryId;
    }

    public InventoryId inventoryId() { return inventoryId; }
}
