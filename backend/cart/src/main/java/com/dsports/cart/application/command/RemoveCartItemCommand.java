package com.dsports.cart.application.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RemoveCartItemCommand(
    @NotNull(message = "Cart item ID is required")
    UUID itemId
) {}
