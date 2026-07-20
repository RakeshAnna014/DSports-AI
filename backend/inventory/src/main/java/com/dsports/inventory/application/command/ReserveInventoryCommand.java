package com.dsports.inventory.application.command;

import com.dsports.inventory.domain.model.InventoryId;
import jakarta.validation.constraints.NotNull;

public record ReserveInventoryCommand(
    @NotNull InventoryId inventoryId,
    int quantity
) {}
