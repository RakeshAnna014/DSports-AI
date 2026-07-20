package com.dsports.inventory.application.command;

import com.dsports.inventory.domain.model.InventoryId;
import jakarta.validation.constraints.NotNull;

public record AdjustInventoryCommand(
    @NotNull InventoryId inventoryId,
    int newQuantity
) {}
