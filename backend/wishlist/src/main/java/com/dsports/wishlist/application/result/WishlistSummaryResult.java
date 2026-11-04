package com.dsports.wishlist.application.result;

import java.util.UUID;

public record WishlistSummaryResult(
    UUID id,
    int totalItems
) {}
