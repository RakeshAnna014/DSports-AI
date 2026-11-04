package com.dsports.review.application.command;

import java.util.UUID;

public record CreateReviewCommand(
    UUID productId,
    UUID orderId,
    UUID userId,
    int rating,
    String title,
    String comment
) {}
