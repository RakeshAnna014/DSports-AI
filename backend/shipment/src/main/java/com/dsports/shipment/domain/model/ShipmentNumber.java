package com.dsports.shipment.domain.model;

import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;

import java.util.Objects;
import java.util.UUID;

public record ShipmentNumber(String value) {
    public ShipmentNumber {
        if (value == null || value.isBlank()) {
            throw new ShipmentDomainException(
                ShipmentErrorCode.INVALID_SHIPMENT_NUMBER,
                "ShipmentNumber must not be null or blank"
            );
        }
    }

    public static ShipmentNumber generate() {
        return new ShipmentNumber("SH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }
}
