package com.dsports.review.application.query;

import java.util.UUID;

public record GetReviewQuery(
    UUID reviewId,
    UUID userId,
    boolean isAdmin
) {}
