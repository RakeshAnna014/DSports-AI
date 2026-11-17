package com.dsports.cricket.application.result;

import java.time.Instant;
import java.util.UUID;

/** A claim. {@code claimantName} and {@code claimantEmail} are filled only for the person who must decide it. */
public record ClaimResult(UUID id, UUID playerId, String playerName, String status, String claimantName, String claimantEmail, Instant createdAt, Instant decidedAt) {
}
