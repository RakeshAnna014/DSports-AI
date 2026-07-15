package com.dsports.identity.application.result;

import java.time.Instant;
import java.util.UUID;

public record AddressResult(
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
) {}
