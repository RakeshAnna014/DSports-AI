package com.dsports.cricket.application.result;

import java.util.UUID;

/** A guest profile that might be you: only a shortened name, so a phone number cannot be used to harvest names. */
public record ClaimableResult(UUID playerId, String displayName) {
}
