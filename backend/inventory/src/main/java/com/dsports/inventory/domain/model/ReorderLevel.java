package com.dsports.inventory.domain.model;

import com.dsports.inventory.domain.exception.InventoryDomainException;
import com.dsports.inventory.domain.exception.InventoryErrorCode;
import com.dsports.shared.domain.kernel.ValueObject;

public record ReorderLevel(int value) implements ValueObject {

    public ReorderLevel {
        if (value < 0) {
            throw new InventoryDomainException(InventoryErrorCode.INVALID_REORDER_LEVEL,
                    "Reorder level must not be negative");
        }
    }

    public static ReorderLevel from(int value) {
        return new ReorderLevel(value);
    }
}
