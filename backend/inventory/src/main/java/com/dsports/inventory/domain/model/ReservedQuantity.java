package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record ReservedQuantity(int value) implements ValueObject {

    public ReservedQuantity {
        if (value < 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_RESERVED_QUANTITY,
                    "Reserved quantity must not be negative");
        }
    }

    public static ReservedQuantity from(int value) {
        return new ReservedQuantity(value);
    }

    public static ReservedQuantity zero() {
        return new ReservedQuantity(0);
    }

    public ReservedQuantity add(Quantity other) {
        return new ReservedQuantity(this.value + other.value());
    }

    public ReservedQuantity subtract(Quantity other) {
        return new ReservedQuantity(this.value - other.value());
    }
}
