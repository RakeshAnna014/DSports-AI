package com.dsports.inventory.application.command;

import com.dsports.inventory.domain.model.InventoryId;
import jakarta.validation.constraints.NotNull;

public record UpdateReorderLevelCommand(
    @NotNull InventoryId inventoryId,
    int reorderLevel
) {}
