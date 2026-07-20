package com.dsports.inventory.application.usecase;

import com.dsports.inventory.application.result.InventoryResult;
import com.dsports.inventory.application.result.InventorySummaryResult;
import com.dsports.inventory.domain.model.InventoryItem;

public final class InventoryResultMapper {

    private InventoryResultMapper() {}

    public static InventoryResult toResult(InventoryItem item) {
        return new InventoryResult(
                item.getId().value(),
                item.getProductId().value(),
                item.getWarehouseId().value(),
                item.getAvailableQuantity().value(),
                item.getReservedQuantity().value(),
                item.getReorderLevel().value(),
                item.getStatus().name(),
                item.getVersion(),
                item.getCreatedAt(),
                item.getUpdatedAt()
        );
    }

    public static InventorySummaryResult toSummary(InventoryItem item) {
        return new InventorySummaryResult(
                item.getId().value(),
                item.getProductId().value(),
                item.getWarehouseId().value(),
                item.getAvailableQuantity().value(),
                item.getReservedQuantity().value(),
                item.getStatus().name()
        );
    }
}
