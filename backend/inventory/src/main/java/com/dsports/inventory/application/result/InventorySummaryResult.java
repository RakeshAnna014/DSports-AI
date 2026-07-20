package com.dsports.inventory.application.result;

import java.util.UUID;

public record InventorySummaryResult(
    UUID id,
    UUID productId,
    UUID warehouseId,
    int availableQuantity,
    int reservedQuantity,
    String status
) {}
