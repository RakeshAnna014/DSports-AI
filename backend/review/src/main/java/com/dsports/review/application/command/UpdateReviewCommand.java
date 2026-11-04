package com.dsports.review.application.command;

import java.util.UUID;

public record UpdateReviewCommand(
    UUID reviewId,
    UUID userId,
    int rating,
    String title,
    String comment
) {}
