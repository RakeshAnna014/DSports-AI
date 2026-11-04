package com.dsports.review.application.query;

import java.util.UUID;

public record GetProductReviewsQuery(
    UUID productId,
    int page,
    int size
) {}
