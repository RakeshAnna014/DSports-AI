package com.dsports.wishlist.application.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WishlistResult(
    UUID id,
    UUID userId,
    int totalItems,
    int version,
    List<WishlistItemResult> items,
    Instant createdAt,
    Instant updatedAt
) {}
