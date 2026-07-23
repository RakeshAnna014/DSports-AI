package com.dsports.order.domain.order.model;

import java.util.Objects;

public record AddressSnapshot(
    String line1,
    String line2,
    String city,
    String state,
    String country,
    String postalCode,
    String fullName,
    String phone
) {
    public AddressSnapshot {
        Objects.requireNonNull(line1, "line1 must not be null");
        Objects.requireNonNull(city, "city must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(country, "country must not be null");
        Objects.requireNonNull(postalCode, "postalCode must not be null");
    }
}
