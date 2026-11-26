package com.dsports.cricket.application.result;

import java.time.Instant;
import java.util.UUID;

/** A claim. {@code claimantName} and {@code claimantEmail} are filled only for the person who must decide it. */
public record ClaimResult(UUID id, UUID playerId, String playerName, String status, String claimantName, String claimantEmail, Instant createdAt, Instant decidedAt, String notice) {

    /** Said to the claimant and to whoever approves: what approval makes public, and how to hide it. */
    public static final String APPROVED_NOTICE = "Once approved, this player's career statistics have a public page and the names on public scorecards link to it. "
        + "The player can hide the page at any time from their profile.";
}
