package com.dsports.shipment.domain.event;

import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.util.UUID;

public class ShipmentInTransitEvent extends DomainEvent {
    private final ShipmentId shipmentId;
    private final UUID orderId;
    private final UUID userId;

    public ShipmentInTransitEvent(ShipmentId shipmentId, UUID orderId, UUID userId) {
        this.shipmentId = shipmentId;
        this.orderId = orderId;
        this.userId = userId;
    }

    public ShipmentId getShipmentId() { return shipmentId; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
}
