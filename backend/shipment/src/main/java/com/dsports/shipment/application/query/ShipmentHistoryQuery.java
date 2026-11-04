package com.dsports.shipment.application.query;

import java.util.UUID;

public record ShipmentHistoryQuery(
    UUID userId,
    boolean isAdmin,
    int page,
    int size
) {}
