package com.dsports.inventory.domain.event;

import com.dsports.inventory.domain.model.InventoryId;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.inventory.domain.model.InventoryStatus;
import com.dsports.shared.domain.kernel.DomainEvent;

public final class ReservationReleasedEvent extends DomainEvent {
    private final InventoryId inventoryId;
    private final Quantity quantity;
    private final InventoryStatus newStatus;

    public ReservationReleasedEvent(InventoryId inventoryId, Quantity quantity, InventoryStatus newStatus) {
        this.inventoryId = inventoryId;
        this.quantity = quantity;
        this.newStatus = newStatus;
    }

    public InventoryId inventoryId() { return inventoryId; }
    public Quantity quantity() { return quantity; }
    public InventoryStatus newStatus() { return newStatus; }
}
