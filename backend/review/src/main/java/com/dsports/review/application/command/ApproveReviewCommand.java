package com.dsports.review.application.command;

import java.util.UUID;

public record ApproveReviewCommand(
    UUID reviewId,
    boolean approve
) {}
