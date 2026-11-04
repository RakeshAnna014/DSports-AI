package com.dsports.shipment.domain.model;

import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;

import java.util.Objects;
import java.util.UUID;

public record TrackingNumber(String value) {
    public TrackingNumber {
        if (value == null || value.isBlank()) {
            throw new ShipmentDomainException(
                ShipmentErrorCode.INVALID_TRACKING_NUMBER,
                "TrackingNumber must not be null or blank"
            );
        }
    }

    public static TrackingNumber generate() {
        return new TrackingNumber("TRK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }
}
