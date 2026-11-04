package com.dsports.review.application.result;

import java.time.Instant;
import java.util.UUID;

public record ReviewResult(
    UUID id,
    UUID productId,
    UUID orderId,
    int rating,
    String title,
    String comment,
    boolean verifiedPurchase,
    int helpfulCount,
    String status,
    Instant createdAt,
    Instant updatedAt,
    int version
) {}
