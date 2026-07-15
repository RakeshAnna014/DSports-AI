package com.dsports.identity.interfaces.dto;

import com.dsports.identity.application.result.AddressResult;

import java.time.Instant;
import java.util.UUID;

public record AddressResponse(
    UUID addressId,
    String type,
    String line1,
    String line2,
    String city,
    String state,
    String country,
    String postalCode,
    boolean isDefault,
    Instant createdAt,
    Instant updatedAt
) {
    public static AddressResponse from(AddressResult result) {
        return new AddressResponse(
            result.addressId(),
            result.type().name(),
            result.line1(),
            result.line2(),
            result.city(),
            result.state(),
            result.country(),
            result.postalCode(),
            result.isDefault(),
            result.createdAt(),
            result.updatedAt()
        );
    }
}
