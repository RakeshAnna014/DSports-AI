package com.dsports.review.application.result;

import java.util.Map;
import java.util.UUID;

public record RatingSummaryResult(
    UUID productId,
    double averageRating,
    int totalReviews,
    Map<Integer, Integer> distribution,
    int rating1Count,
    int rating2Count,
    int rating3Count,
    int rating4Count,
    int rating5Count
) {}
