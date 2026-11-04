package com.dsports.support.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A message on a case. {@code internal} messages are staff notes: they are never returned to the customer.
 */
public record CaseMessage(UUID id, UUID caseId, String authorType, UUID authorId, boolean internal, String body, Instant createdAt) {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String STAFF = "STAFF";
    public static final String SYSTEM = "SYSTEM";

    public static CaseMessage of(UUID caseId, String authorType, UUID authorId, boolean internal, String body, Instant now) {
        return new CaseMessage(UUID.randomUUID(), caseId, authorType, authorId, internal, body, now);
    }
}
