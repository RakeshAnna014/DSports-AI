package com.dsports.support.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only history of a case: every creation, message, status change, escalation, assignment and resolution, with who did it.
 * {@code customerVisible} controls whether the customer's timeline shows it (internal notes and assignment identity are not).
 */
public record CaseEvent(UUID id, UUID caseId, String eventType, String actorType, UUID actorId, String fromStatus, String toStatus,
                        String detail, boolean customerVisible, Instant createdAt) {

    public static final String CREATED = "CREATED";
    public static final String MESSAGE_ADDED = "MESSAGE_ADDED";
    public static final String NOTE_ADDED = "NOTE_ADDED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String ESCALATION_REQUESTED = "ESCALATION_REQUESTED";
    public static final String ESCALATION_ACCEPTED = "ESCALATION_ACCEPTED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String PRIORITY_CHANGED = "PRIORITY_CHANGED";
    public static final String RESOLVED = "RESOLVED";
    public static final String REOPENED = "REOPENED";

    public static CaseEvent of(UUID caseId, String eventType, String actorType, UUID actorId, String from, String to, String detail,
                               boolean customerVisible, Instant now) {
        var d = detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        return new CaseEvent(UUID.randomUUID(), caseId, eventType, actorType, actorId, from, to, d, customerVisible, now);
    }
}
