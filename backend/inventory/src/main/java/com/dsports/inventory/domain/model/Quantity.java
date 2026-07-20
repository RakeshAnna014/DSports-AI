package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record Quantity(int value) implements ValueObject {

    public Quantity {
        if (value < 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_QUANTITY,
                    "Quantity must not be negative");
        }
    }

    public static Quantity from(int value) {
        return new Quantity(value);
    }

    public static Quantity zero() {
        return new Quantity(0);
    }

    public Quantity add(Quantity other) {
        return new Quantity(this.value + other.value);
    }

    public Quantity subtract(Quantity other) {
        return new Quantity(this.value - other.value);
    }

    public boolean isGreaterThanOrEqualTo(Quantity other) {
        return this.value >= other.value;
    }
}
