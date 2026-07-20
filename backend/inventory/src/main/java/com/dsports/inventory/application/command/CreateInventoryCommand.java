package com.dsports.inventory.application.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateInventoryCommand(
    @NotNull UUID productId,
    @NotNull UUID warehouseId,
    int initialQuantity,
    int reorderLevel
) {}
