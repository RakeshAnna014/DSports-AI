package com.dsports.shipment.application.query;

import java.util.UUID;

public record GetShipmentQuery(
    UUID shipmentId,
    UUID userId,
    boolean isAdmin
) {}
