package com.dsports.wishlist.application.command;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MoveWishlistItemToCartCommand(
    @NotNull(message = "Wishlist item ID is required")
    UUID itemId
) {}
