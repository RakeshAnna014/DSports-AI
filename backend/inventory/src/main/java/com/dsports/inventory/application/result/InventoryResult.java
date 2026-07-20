package com.dsports.inventory.application.result;

import java.time.Instant;
import java.util.UUID;

public record InventoryResult(
    UUID id,
    UUID productId,
    UUID warehouseId,
    int availableQuantity,
    int reservedQuantity,
    int reorderLevel,
    String status,
    int version,
    Instant createdAt,
    Instant updatedAt
) {}
