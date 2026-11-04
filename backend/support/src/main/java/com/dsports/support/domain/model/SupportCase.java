package com.dsports.support.domain.model;

import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's complaint or request, tracked from creation to resolution. The aggregate owns the lifecycle rules: nothing outside
 * it can put a case into a state the transition table does not allow. The customer identity is set once, from the authenticated
 * context, and never changes.
 */
public final class SupportCase {

    /** Everything needed to open a case. Facts about the order come from the backend's own records, not from the caller. */
    public record OpenData(UUID userId, CaseCategory category, String subcategory, String subject, String description,
                           UUID orderId, String orderNumber, UUID productId, String productName, UUID paymentId, UUID returnId,
                           String refundReference, String orderStatus, String shipmentStatus, BigDecimal orderTotal, String currency,
                           String investigationSummary, List<EvidenceItem> evidence, List<String> policyCitations,
                           List<String> missingInformation, String resolutionRecommendation, String escalationReason,
                           boolean safetyRelated, String source, String idempotencyKey) {}

    private final UUID id;
    private final String caseNumber;
    private final UUID userId;
    private final CaseCategory category;
    private final String subcategory;
    private CasePriority priority;
    private List<String> priorityReasons;
    private CaseStatus status;
    private final String subject;
    private final String description;
    private final UUID orderId;
    private final String orderNumber;
    private final UUID productId;
    private final String productName;
    private final UUID paymentId;
    private final UUID returnId;
    private final String refundReference;
    private final String orderStatusAtCreation;
    private final String shipmentStatusAtCreation;
    private final BigDecimal orderTotal;
    private final String currency;
    private final String investigationSummary;
    private final List<EvidenceItem> evidence;
    private final List<String> policyCitations;
    private final List<String> missingInformation;
    private final String resolutionRecommendation;
    private ResolutionOutcome resolutionOutcome;
    private String resolutionNote;
    private UUID resolvedBy;
    private String assignedQueue;
    private UUID assignedTo;
    private EscalationState escalationState;
    private String escalationReason;
    private final boolean safetyRelated;
    private final String source;
    private final String idempotencyKey;
    private int reopenCount;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    private SupportCase(UUID id, String caseNumber, OpenData d, CasePriority priority, List<String> priorityReasons, CaseStatus status,
                        ResolutionOutcome outcome, String resolutionNote, UUID resolvedBy, String queue, UUID assignedTo,
                        EscalationState escalationState, String escalationReason, int reopenCount, int version, Instant createdAt,
                        Instant updatedAt, Instant resolvedAt) {
        this.id = Objects.requireNonNull(id);
        this.caseNumber = Objects.requireNonNull(caseNumber);
        this.userId = Objects.requireNonNull(d.userId());
        this.category = Objects.requireNonNull(d.category());
        this.subcategory = d.subcategory();
        this.priority = priority;
        this.priorityReasons = priorityReasons;
        this.status = status;
        this.subject = d.subject();
        this.description = d.description();
        this.orderId = d.orderId();
        this.orderNumber = d.orderNumber();
        this.productId = d.productId();
        this.productName = d.productName();
        this.paymentId = d.paymentId();
        this.returnId = d.returnId();
        this.refundReference = d.refundReference();
        this.orderStatusAtCreation = d.orderStatus();
        this.shipmentStatusAtCreation = d.shipmentStatus();
        this.orderTotal = d.orderTotal();
        this.currency = d.currency();
        this.investigationSummary = d.investigationSummary();
        this.evidence = new ArrayList<>(d.evidence());
        this.policyCitations = List.copyOf(d.policyCitations());
        this.missingInformation = List.copyOf(d.missingInformation());
        this.resolutionRecommendation = d.resolutionRecommendation();
        this.resolutionOutcome = outcome;
        this.resolutionNote = resolutionNote;
        this.resolvedBy = resolvedBy;
        this.assignedQueue = queue;
        this.assignedTo = assignedTo;
        this.escalationState = escalationState;
        this.escalationReason = escalationReason;
        this.safetyRelated = d.safetyRelated();
        this.source = d.source();
        this.idempotencyKey = d.idempotencyKey();
        this.reopenCount = reopenCount;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.resolvedAt = resolvedAt;
    }

    // ------------------------------------------------------------------------------------------------ creation

    public static SupportCase open(String caseNumber, OpenData d, CasePriority priority, List<String> priorityReasons, Instant now) {
        require(d.subject() != null && !d.subject().isBlank() && d.subject().length() <= 200, "A subject of 1-200 characters is required");
        require(d.description() != null && !d.description().isBlank() && d.description().length() <= 2000,
                "A description of 1-2000 characters is required");
        require(d.idempotencyKey() != null && !d.idempotencyKey().isBlank() && d.idempotencyKey().length() <= 100,
                "An Idempotency-Key of 1-100 characters is required");
        require(d.investigationSummary() == null || d.investigationSummary().length() <= 4000, "Investigation summary is too long");
        var escalation = d.escalationReason() == null || d.escalationReason().isBlank() ? EscalationState.NONE : EscalationState.REQUESTED;
        return new SupportCase(UUID.randomUUID(), caseNumber, d, priority, priorityReasons, CaseStatus.OPEN, null, null, null,
                "GENERAL_SUPPORT", null, escalation, escalation == EscalationState.NONE ? null : clip(d.escalationReason(), 500), 0, 0, now, now, null);
    }

    public static SupportCase reconstitute(UUID id, String caseNumber, OpenData d, CasePriority priority, List<String> priorityReasons,
                                           CaseStatus status, ResolutionOutcome outcome, String resolutionNote, UUID resolvedBy,
                                           String queue, UUID assignedTo, EscalationState escalationState, String escalationReason,
                                           int reopenCount, int version, Instant createdAt, Instant updatedAt, Instant resolvedAt) {
        return new SupportCase(id, caseNumber, d, priority, priorityReasons, status, outcome, resolutionNote, resolvedBy, queue,
                assignedTo, escalationState, escalationReason, reopenCount, version, createdAt, updatedAt, resolvedAt);
    }

    // ------------------------------------------------------------------------------------------------ behaviour

    /** A plain status change by staff. Resolving has its own method because it needs an outcome. */
    public CaseStatus changeStatus(CaseStatus target, Instant now) {
        require(target != CaseStatus.RESOLVED, "Use resolve to resolve a case: an outcome is required");
        var from = status;
        move(target);
        updatedAt = now;
        return from;
    }

    public CaseStatus resolve(ResolutionOutcome outcome, String note, UUID staffId, Instant now) {
        require(outcome != null, "A resolution outcome is required");
        require(note != null && !note.isBlank() && note.length() <= 1000, "A resolution note of 1-1000 characters is required");
        var from = status;
        move(CaseStatus.RESOLVED);
        this.resolutionOutcome = outcome;
        this.resolutionNote = note.trim();
        this.resolvedBy = staffId;
        this.resolvedAt = now;
        this.updatedAt = now;
        return from;
    }

    /**
     * Reopens a resolved case. A customer may do so only inside {@code window} after resolution; staff may always reopen.
     */
    public void reopen(boolean byStaff, Duration window, Instant now) {
        if (status != CaseStatus.RESOLVED) {
            throw new SupportDomainException(SupportErrorCode.REOPEN_NOT_ALLOWED, "Only a resolved case can be reopened");
        }
        if (!byStaff && (resolvedAt == null || resolvedAt.plus(window).isBefore(now))) {
            throw new SupportDomainException(SupportErrorCode.REOPEN_NOT_ALLOWED,
                    "This case was resolved more than " + window.toDays() + " days ago and can no longer be reopened; please raise a new case");
        }
        move(CaseStatus.INVESTIGATING);
        this.resolutionOutcome = null;
        this.resolutionNote = null;
        this.resolvedBy = null;
        this.resolvedAt = null;
        this.reopenCount++;
        this.updatedAt = now;
    }

    /** Records that a human should handle this case. Returns false when that was already requested (idempotent). */
    public boolean requestEscalation(String reason, Instant now) {
        requireUnresolved();
        require(reason != null && !reason.isBlank(), "An escalation reason is required");
        if (escalationState != EscalationState.NONE) {
            return false;
        }
        this.escalationState = EscalationState.REQUESTED;
        this.escalationReason = clip(reason.trim(), 500);
        this.updatedAt = now;
        return true;
    }

    /**
     * Staff takes the case (or a queue is chosen). Taking an escalated case is what turns REQUESTED into ACCEPTED. Returns the
     * previous status when taking an OPEN case started the investigation, otherwise null.
     */
    public CaseStatus assign(String queue, UUID staffId, Instant now) {
        requireUnresolved();
        if (queue != null && !queue.isBlank()) {
            require(queue.length() <= 40, "Queue name is too long");
            this.assignedQueue = queue.trim();
        }
        CaseStatus startedFrom = null;
        if (staffId != null) {
            this.assignedTo = staffId;
            if (escalationState == EscalationState.REQUESTED) {
                escalationState = EscalationState.ACCEPTED;
            }
            if (status == CaseStatus.OPEN) {
                startedFrom = status;
                move(CaseStatus.INVESTIGATING);
            }
        }
        this.updatedAt = now;
        return startedFrom;
    }

    /** A customer message: not allowed on a resolved case (reopen first); it ends a wait for the customer. Returns true if status changed. */
    public boolean onCustomerMessage(Instant now) {
        if (status == CaseStatus.RESOLVED) {
            throw new SupportDomainException(SupportErrorCode.CASE_RESOLVED,
                    "This case is resolved. Reopen it if the problem is not solved, or raise a new case");
        }
        this.updatedAt = now;
        if (status == CaseStatus.WAITING_FOR_CUSTOMER) {
            move(CaseStatus.INVESTIGATING);
            return true;
        }
        return false;
    }

    /** Staff override of the computed priority; the reason is kept in the priority reasons so the case still explains itself. */
    public CasePriority changePriority(CasePriority target, String reason, Instant now) {
        requireUnresolved();
        require(target != null, "A priority is required");
        require(reason != null && !reason.isBlank() && reason.length() <= 500, "A reason of 1-500 characters is required");
        var from = priority;
        this.priority = target;
        var reasons = new ArrayList<>(priorityReasons);
        reasons.add("Changed by support from " + from + " to " + target + ": " + reason.trim());
        this.priorityReasons = List.copyOf(reasons);
        this.updatedAt = now;
        return from;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
    }

    private void move(CaseStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new SupportDomainException(SupportErrorCode.INVALID_STATUS_TRANSITION,
                    "Case " + caseNumber + " is " + status + " and cannot become " + target);
        }
        this.status = target;
    }

    private void requireUnresolved() {
        if (status == CaseStatus.RESOLVED) {
            throw new SupportDomainException(SupportErrorCode.CASE_RESOLVED, "Case " + caseNumber + " is already resolved");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, message);
        }
    }

    private static String clip(String value, int max) {
        return value == null ? null : (value.length() > max ? value.substring(0, max) : value);
    }

    // ------------------------------------------------------------------------------------------------ accessors

    public boolean isOwnedBy(UUID user) { return userId.equals(user); }
    public UUID getId() { return id; }
    public String getCaseNumber() { return caseNumber; }
    public UUID getUserId() { return userId; }
    public CaseCategory getCategory() { return category; }
    public String getSubcategory() { return subcategory; }
    public CasePriority getPriority() { return priority; }
    public List<String> getPriorityReasons() { return priorityReasons; }
    public CaseStatus getStatus() { return status; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public UUID getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getProductId() { return productId; }
    public String getProductName() { return productName; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getReturnId() { return returnId; }
    public String getRefundReference() { return refundReference; }
    public String getOrderStatusAtCreation() { return orderStatusAtCreation; }
    public String getShipmentStatusAtCreation() { return shipmentStatusAtCreation; }
    public BigDecimal getOrderTotal() { return orderTotal; }
    public String getCurrency() { return currency; }
    public String getInvestigationSummary() { return investigationSummary; }
    public List<EvidenceItem> getEvidence() { return List.copyOf(evidence); }
    public List<String> getPolicyCitations() { return policyCitations; }
    public List<String> getMissingInformation() { return missingInformation; }
    public String getResolutionRecommendation() { return resolutionRecommendation; }
    public ResolutionOutcome getResolutionOutcome() { return resolutionOutcome; }
    public String getResolutionNote() { return resolutionNote; }
    public UUID getResolvedBy() { return resolvedBy; }
    public String getAssignedQueue() { return assignedQueue; }
    public UUID getAssignedTo() { return assignedTo; }
    public EscalationState getEscalationState() { return escalationState; }
    public String getEscalationReason() { return escalationReason; }
    public boolean isSafetyRelated() { return safetyRelated; }
    public String getSource() { return source; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getReopenCount() { return reopenCount; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }

    /** Adds a staff finding as evidence (used when a case is resolved with supporting references). */
    public void addEvidence(EvidenceItem item) {
        this.evidence.add(item);
    }
}
