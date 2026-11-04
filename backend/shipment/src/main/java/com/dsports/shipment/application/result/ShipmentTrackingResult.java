package com.dsports.shipment.application.result;

import java.time.Instant;
import java.util.UUID;

public record ShipmentTrackingResult(
    UUID trackingId,
    UUID shipmentId,
    String status,
    String location,
    String description,
    Instant eventTime
) {}
