package com.dsports.shipment.application.command;

import com.dsports.shipment.domain.model.ShipmentStatus;
import java.util.UUID;

public record UpdateShipmentStatusCommand(
    UUID shipmentId,
    ShipmentStatus newStatus,
    String reason
) {}
