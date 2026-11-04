package com.dsports.support.infrastructure.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persistence shape of a support case. Package-private fields: only the adapter maps to and from it. */
@Table("support_cases")
public class SupportCaseEntity {

    @Id
    UUID id;
    @Column("case_number")
    String caseNumber;
    @Column("user_id")
    UUID userId;
    String category;
    String subcategory;
    String priority;
    @Column("priority_reasons")
    String priorityReasons;
    String status;
    String subject;
    String description;
    @Column("order_id")
    UUID orderId;
    @Column("order_number")
    String orderNumber;
    @Column("product_id")
    UUID productId;
    @Column("product_name")
    String productName;
    @Column("payment_id")
    UUID paymentId;
    @Column("return_id")
    UUID returnId;
    @Column("refund_reference")
    String refundReference;
    @Column("order_status_at_creation")
    String orderStatusAtCreation;
    @Column("shipment_status_at_creation")
    String shipmentStatusAtCreation;
    @Column("order_total")
    BigDecimal orderTotal;
    String currency;
    @Column("investigation_summary")
    String investigationSummary;
    String evidence;
    @Column("policy_citations")
    String policyCitations;
    @Column("missing_information")
    String missingInformation;
    @Column("resolution_recommendation")
    String resolutionRecommendation;
    @Column("resolution_outcome")
    String resolutionOutcome;
    @Column("resolution_note")
    String resolutionNote;
    @Column("resolved_by")
    UUID resolvedBy;
    @Column("assigned_queue")
    String assignedQueue;
    @Column("assigned_to")
    UUID assignedTo;
    @Column("escalation_state")
    String escalationState;
    @Column("escalation_reason")
    String escalationReason;
    @Column("safety_related")
    boolean safetyRelated;
    String source;
    @Column("idempotency_key")
    String idempotencyKey;
    @Column("reopen_count")
    int reopenCount;
    @Version
    int version;
    @Column("created_at")
    Instant createdAt;
    @Column("updated_at")
    Instant updatedAt;
    @Column("resolved_at")
    Instant resolvedAt;
}
