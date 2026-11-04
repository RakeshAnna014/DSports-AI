package com.dsports.shipment.application.query;

import java.util.UUID;

/** Tracking reveals the delivery address, so only the shipment's owner or an administrator may track it. */
public record TrackShipmentQuery(
    String trackingNumber,
    UUID userId,
    boolean isAdmin
) {}
