package com.dsports.wishlist.interfaces;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddWishlistItemRequest(
    @NotNull(message = "Product ID is required")
    UUID productId
) {}
