package com.dsports.wishlist.application.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddWishlistItemCommand(
    @NotNull(message = "Product ID is required")
    UUID productId
) {}
