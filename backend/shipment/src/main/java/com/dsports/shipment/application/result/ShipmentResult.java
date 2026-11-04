package com.dsports.shipment.application.result;

import java.time.Instant;
import java.util.UUID;

public record ShipmentResult(
    UUID id,
    String shipmentNumber,
    String trackingNumber,
    UUID orderId,
    UUID userId,
    String courierProvider,
    String status,
    String street,
    String city,
    String state,
    String zipCode,
    String country,
    Instant estimatedDeliveryDate,
    Instant actualDeliveryDate,
    Instant shippedAt,
    Instant deliveredAt,
    Instant createdAt,
    Instant updatedAt,
    int version
) {}
