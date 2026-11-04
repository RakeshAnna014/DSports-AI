package com.dsports.shipment.domain.model;

import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;

public record ShippingAddress(String street, String city, String state, String zipCode, String country) {
    public ShippingAddress {
        if (street == null || street.isBlank()) {
            throw new ShipmentDomainException(ShipmentErrorCode.INVALID_ADDRESS, "Street must not be null or blank");
        }
        if (city == null || city.isBlank()) {
            throw new ShipmentDomainException(ShipmentErrorCode.INVALID_ADDRESS, "City must not be null or blank");
        }
        if (state == null || state.isBlank()) {
            throw new ShipmentDomainException(ShipmentErrorCode.INVALID_ADDRESS, "State must not be null or blank");
        }
        if (zipCode == null || zipCode.isBlank()) {
            throw new ShipmentDomainException(ShipmentErrorCode.INVALID_ADDRESS, "ZipCode must not be null or blank");
        }
        if (country == null || country.isBlank()) {
            throw new ShipmentDomainException(ShipmentErrorCode.INVALID_ADDRESS, "Country must not be null or blank");
        }
    }
}
