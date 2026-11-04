package com.dsports.support.interfaces;

import com.dsports.support.application.usecase.CreateSupportCaseUseCase;
import com.dsports.support.application.usecase.CustomerCaseUseCase;
import com.dsports.support.application.usecase.StaffCaseUseCase;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.EvidenceItem;
import com.dsports.support.domain.model.SupportCase;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response shapes. {@link Customer} is what the owner of a case may see; {@link Staff} adds internal notes, priority reasons and
 * identities. Nothing staff-only is reachable from the customer shapes: they are different types built by different mappers.
 */
public final class SupportResponses {

    private SupportResponses() {}

    public record Escalation(String state, String reason, boolean humanAccepted) {}

    public record Summary(UUID id, String caseNumber, String category, String status, String priority, String subject, String orderNumber,
                          Escalation escalation, Instant createdAt, Instant updatedAt, Instant resolvedAt) {}

    public record CustomerMessage(UUID id, String from, String body, Instant createdAt) {}

    public record CustomerEvent(String type, String fromStatus, String toStatus, String detail, Instant at) {}

    public record Customer(UUID id, String caseNumber, String category, String subcategory, String status, String priority, String subject,
                           String description, UUID orderId, String orderNumber, UUID productId, String productName, String orderStatusAtCreation,
                           String shipmentStatusAtCreation, String investigationSummary, List<EvidenceItem> evidence, List<String> policyCitations,
                           List<String> missingInformation, String resolutionRecommendation, String resolutionOutcome, String resolutionNote,
                           Escalation escalation, boolean assignedToSupportTeam, boolean canReopen, int reopenWindowDays, int version,
                           Instant createdAt, Instant updatedAt, Instant resolvedAt, List<CustomerMessage> messages, List<CustomerEvent> timeline) {}

    public record CreateResult(String outcome, Customer supportCase) {}

    public record StaffMessage(UUID id, String authorType, UUID authorId, boolean internal, String body, Instant createdAt) {}

    public record StaffEvent(String type, String actorType, UUID actorId, String fromStatus, String toStatus, String detail, boolean customerVisible,
                             Instant at) {}

    public record Staff(UUID id, String caseNumber, UUID customerId, String category, String subcategory, String status, String priority,
                        List<String> priorityReasons, String subject, String description, UUID orderId, String orderNumber, UUID productId,
                        String productName, UUID paymentId, UUID returnId, String refundReference, String orderStatusAtCreation,
                        String shipmentStatusAtCreation, BigDecimal orderTotal, String currency, String investigationSummary,
                        List<EvidenceItem> evidence, List<String> policyCitations, List<String> missingInformation, String resolutionRecommendation,
                        String resolutionOutcome, String resolutionNote, UUID resolvedBy, String assignedQueue, UUID assignedTo, Escalation escalation,
                        boolean safetyRelated, String source, int reopenCount, int version, Instant createdAt, Instant updatedAt, Instant resolvedAt,
                        List<StaffMessage> messages, List<StaffEvent> events) {}

    public record StaffPage(List<Summary> items, long total, int page, int size) {}

    // ------------------------------------------------------------------------------------------------ mapping

    static Escalation escalation(SupportCase c) {
        return new Escalation(c.getEscalationState().name(), c.getEscalationReason(), c.getEscalationState() == com.dsports.support.domain.model.EscalationState.ACCEPTED);
    }

    static Summary summary(SupportCase c) {
        return new Summary(c.getId(), c.getCaseNumber(), c.getCategory().name(), c.getStatus().name(), c.getPriority().name(), c.getSubject(),
            c.getOrderNumber(), escalation(c), c.getCreatedAt(), c.getUpdatedAt(), c.getResolvedAt());
    }

    static Customer customer(CustomerCaseUseCase.CaseView v, Duration reopenWindow, Instant now) {
        var c = v.supportCase();
        boolean canReopen = c.getStatus() == CaseStatus.RESOLVED && c.getResolvedAt() != null && !c.getResolvedAt().plus(reopenWindow).isBefore(now);
        return new Customer(c.getId(), c.getCaseNumber(), c.getCategory().name(), c.getSubcategory(), c.getStatus().name(), c.getPriority().name(),
            c.getSubject(), c.getDescription(), c.getOrderId(), c.getOrderNumber(), c.getProductId(), c.getProductName(), c.getOrderStatusAtCreation(),
            c.getShipmentStatusAtCreation(), c.getInvestigationSummary(), c.getEvidence(), c.getPolicyCitations(), c.getMissingInformation(),
            c.getResolutionRecommendation(), c.getResolutionOutcome() == null ? null : c.getResolutionOutcome().name(), c.getResolutionNote(),
            escalation(c), c.getAssignedTo() != null, canReopen, (int) reopenWindow.toDays(), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt(),
            c.getResolvedAt(),
            v.messages().stream().map(m -> new CustomerMessage(m.id(), CaseMessage.CUSTOMER.equals(m.authorType()) ? "CUSTOMER" : "SUPPORT", m.body(), m.createdAt())).toList(),
            v.events().stream().map(e -> new CustomerEvent(e.eventType(), e.fromStatus(), e.toStatus(), e.detail(), e.createdAt())).toList());
    }

    static CreateResult created(CreateSupportCaseUseCase.Result r, CustomerCaseUseCase.CaseView view, Duration window, Instant now) {
        return new CreateResult(r.outcome().name(), customer(view, window, now));
    }

    static Staff staff(StaffCaseUseCase.StaffView v) {
        var c = v.supportCase();
        return new Staff(c.getId(), c.getCaseNumber(), c.getUserId(), c.getCategory().name(), c.getSubcategory(), c.getStatus().name(), c.getPriority().name(),
            c.getPriorityReasons(), c.getSubject(), c.getDescription(), c.getOrderId(), c.getOrderNumber(), c.getProductId(), c.getProductName(),
            c.getPaymentId(), c.getReturnId(), c.getRefundReference(), c.getOrderStatusAtCreation(), c.getShipmentStatusAtCreation(), c.getOrderTotal(),
            c.getCurrency(), c.getInvestigationSummary(), c.getEvidence(), c.getPolicyCitations(), c.getMissingInformation(),
            c.getResolutionRecommendation(), c.getResolutionOutcome() == null ? null : c.getResolutionOutcome().name(), c.getResolutionNote(),
            c.getResolvedBy(), c.getAssignedQueue(), c.getAssignedTo(), escalation(c), c.isSafetyRelated(), c.getSource(), c.getReopenCount(),
            c.getVersion(), c.getCreatedAt(), c.getUpdatedAt(), c.getResolvedAt(),
            v.messages().stream().map(m -> new StaffMessage(m.id(), m.authorType(), m.authorId(), m.internal(), m.body(), m.createdAt())).toList(),
            v.events().stream().map((CaseEvent e) -> new StaffEvent(e.eventType(), e.actorType(), e.actorId(), e.fromStatus(), e.toStatus(), e.detail(),
                e.customerVisible(), e.createdAt())).toList());
    }
}
