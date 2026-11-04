package com.dsports.review.application.command;

import java.util.UUID;

public record HelpfulVoteCommand(
    UUID reviewId,
    UUID userId
) {}
