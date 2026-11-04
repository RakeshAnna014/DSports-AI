package com.dsports.wishlist.application.result;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WishlistItemResult(
    UUID id,
    UUID productId,
    String productName,
    String productImage,
    BigDecimal currentPrice,
    Instant addedAt
) {}
