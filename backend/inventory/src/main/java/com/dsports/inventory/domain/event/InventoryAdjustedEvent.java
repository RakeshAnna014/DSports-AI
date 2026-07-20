package com.dsports.inventory.domain.event;

import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.inventory.domain.model.InventoryStatus;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class InventoryAdjustedEvent extends DomainEvent {
    private final InventoryId inventoryId;
    private final Quantity previousAvailable;
    private final Quantity newAvailable;
    private final InventoryStatus newStatus;

    public InventoryAdjustedEvent(InventoryId inventoryId, Quantity previousAvailable, Quantity newAvailable, InventoryStatus newStatus) {
        this.inventoryId = inventoryId;
        this.previousAvailable = previousAvailable;
        this.newAvailable = newAvailable;
        this.newStatus = newStatus;
    }

    public InventoryId inventoryId() { return inventoryId; }
    public Quantity previousAvailable() { return previousAvailable; }
    public Quantity newAvailable() { return newAvailable; }
    public InventoryStatus newStatus() { return newStatus; }
}
