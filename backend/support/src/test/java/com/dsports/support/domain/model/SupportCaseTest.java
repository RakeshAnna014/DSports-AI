package com.dsports.support.domain.model;

import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupportCaseTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();

    private static SupportCase.OpenData data(String escalationReason) {
        return new SupportCase.OpenData(CUSTOMER, CaseCategory.MISSING_DELIVERY, null, "Order says delivered", "I did not receive it", UUID.randomUUID(), "ORD-1",
            null, null, null, null, null, "DELIVERED", "DELIVERED", null, "INR", null, List.of(), List.of(), List.of(), null, escalationReason, false, "WEB", "key-1");
    }

    private static SupportCase open() {
        return SupportCase.open("CASE-20261012-000001", data(null), CasePriority.MEDIUM, List.of("base"), T0);
    }

    @Test
    void a_new_case_is_open_and_not_escalated() {
        var c = open();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.getEscalationState()).isEqualTo(EscalationState.NONE);
        assertThat(c.isOwnedBy(CUSTOMER)).isTrue();
        assertThat(c.isOwnedBy(UUID.randomUUID())).isFalse();
    }

    @Test
    void an_escalation_reason_at_creation_is_a_request_not_an_acceptance() {
        var c = SupportCase.open("CASE-20261012-000002", data("Needs a human"), CasePriority.MEDIUM, List.of(), T0);
        assertThat(c.getEscalationState()).isEqualTo(EscalationState.REQUESTED);
    }

    @Test
    void taking_an_escalated_case_is_what_accepts_it_and_it_starts_the_investigation() {
        var c = SupportCase.open("CASE-20261012-000003", data("Needs a human"), CasePriority.MEDIUM, List.of(), T0);
        var started = c.assign("PAYMENTS", STAFF, T0.plusSeconds(60));
        assertThat(started).isEqualTo(CaseStatus.OPEN);
        assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(c.getEscalationState()).isEqualTo(EscalationState.ACCEPTED);
        assertThat(c.getAssignedTo()).isEqualTo(STAFF);
    }

    @Test
    void setting_only_a_queue_does_not_accept_an_escalation() {
        var c = SupportCase.open("CASE-20261012-000004", data("Needs a human"), CasePriority.MEDIUM, List.of(), T0);
        c.assign("PAYMENTS", null, T0);
        assertThat(c.getEscalationState()).isEqualTo(EscalationState.REQUESTED);
        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void escalation_requests_are_idempotent() {
        var c = open();
        assertThat(c.requestEscalation("Customer disputes the result", T0)).isTrue();
        assertThat(c.requestEscalation("again", T0)).isFalse();
        assertThat(c.getEscalationReason()).isEqualTo("Customer disputes the result");
    }

    @Test
    void invalid_transitions_are_rejected() {
        var c = open();
        assertThatThrownBy(() -> c.changeStatus(CaseStatus.WAITING_FOR_EXTERNAL_PARTY, T0))
            .isInstanceOf(SupportDomainException.class).extracting("errorCode").isEqualTo(SupportErrorCode.INVALID_STATUS_TRANSITION);
        c.changeStatus(CaseStatus.INVESTIGATING, T0);
        assertThatThrownBy(() -> c.changeStatus(CaseStatus.OPEN, T0)).isInstanceOf(SupportDomainException.class);
    }

    @Test
    void a_case_cannot_be_resolved_through_a_plain_status_change() {
        assertThatThrownBy(() -> open().changeStatus(CaseStatus.RESOLVED, T0))
            .isInstanceOf(SupportDomainException.class).extracting("errorCode").isEqualTo(SupportErrorCode.VALIDATION_ERROR);
    }

    @Test
    void resolving_needs_an_outcome_and_a_note_and_records_who() {
        var c = open();
        assertThatThrownBy(() -> c.resolve(null, "note", STAFF, T0)).isInstanceOf(SupportDomainException.class);
        assertThatThrownBy(() -> c.resolve(ResolutionOutcome.ANSWERED, " ", STAFF, T0)).isInstanceOf(SupportDomainException.class);
        c.resolve(ResolutionOutcome.DELIVERY_CONFIRMED, "Courier confirmed delivery to the front desk", STAFF, T0.plusSeconds(5));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(c.getResolvedBy()).isEqualTo(STAFF);
        assertThat(c.getResolvedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void a_resolved_case_rejects_customer_messages_and_escalation_until_it_is_reopened() {
        var c = open();
        c.resolve(ResolutionOutcome.ANSWERED, "Explained", STAFF, T0);
        assertThatThrownBy(() -> c.onCustomerMessage(T0)).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.CASE_RESOLVED);
        assertThatThrownBy(() -> c.requestEscalation("why", T0)).isInstanceOf(SupportDomainException.class);
    }

    @Test
    void a_customer_can_reopen_only_inside_the_window_but_staff_always_can() {
        var window = Duration.ofDays(7);
        var c = open();
        c.resolve(ResolutionOutcome.ANSWERED, "Explained", STAFF, T0);
        assertThatThrownBy(() -> c.reopen(false, window, T0.plus(Duration.ofDays(8))))
            .isInstanceOf(SupportDomainException.class).extracting("errorCode").isEqualTo(SupportErrorCode.REOPEN_NOT_ALLOWED);
        c.reopen(true, window, T0.plus(Duration.ofDays(8)));
        assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(c.getReopenCount()).isEqualTo(1);
        assertThat(c.getResolutionOutcome()).isNull();
        c.resolve(ResolutionOutcome.ANSWERED, "Explained again", STAFF, T0.plus(Duration.ofDays(9)));
        c.reopen(false, window, T0.plus(Duration.ofDays(10)));
        assertThat(c.getReopenCount()).isEqualTo(2);
    }

    @Test
    void staff_priority_change_is_recorded_with_its_reason() {
        var c = open();
        var from = c.changePriority(CasePriority.URGENT, "Customer is travelling", T0.plusSeconds(60));
        assertThat(from).isEqualTo(CasePriority.MEDIUM);
        assertThat(c.getPriority()).isEqualTo(CasePriority.URGENT);
        assertThat(c.getPriorityReasons()).hasSize(2);
        assertThat(c.getPriorityReasons().get(1)).contains("MEDIUM to URGENT").contains("Customer is travelling");
    }

    @Test
    void priority_change_needs_a_reason_and_an_unresolved_case() {
        var c = open();
        assertThatThrownBy(() -> c.changePriority(CasePriority.HIGH, " ", T0)).isInstanceOf(SupportDomainException.class);
        c.resolve(ResolutionOutcome.ANSWERED, "Explained", STAFF, T0);
        assertThatThrownBy(() -> c.changePriority(CasePriority.HIGH, "late", T0)).isInstanceOf(SupportDomainException.class);
    }

    @Test
    void only_a_resolved_case_can_be_reopened() {
        assertThatThrownBy(() -> open().reopen(true, Duration.ofDays(7), T0)).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.REOPEN_NOT_ALLOWED);
    }

    @Test
    void a_customer_reply_ends_the_wait_for_the_customer() {
        var c = open();
        c.changeStatus(CaseStatus.WAITING_FOR_CUSTOMER, T0);
        assertThat(c.onCustomerMessage(T0.plusSeconds(1))).isTrue();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(c.onCustomerMessage(T0.plusSeconds(2))).isFalse();
    }

    @Test
    void every_transition_in_the_table_is_exercised() {
        var allowed = java.util.Map.of(
            CaseStatus.OPEN, List.of(CaseStatus.INVESTIGATING, CaseStatus.WAITING_FOR_CUSTOMER, CaseStatus.RESOLVED),
            CaseStatus.INVESTIGATING, List.of(CaseStatus.WAITING_FOR_CUSTOMER, CaseStatus.WAITING_FOR_EXTERNAL_PARTY, CaseStatus.RESOLVED),
            CaseStatus.WAITING_FOR_CUSTOMER, List.of(CaseStatus.INVESTIGATING, CaseStatus.RESOLVED),
            CaseStatus.WAITING_FOR_EXTERNAL_PARTY, List.of(CaseStatus.INVESTIGATING, CaseStatus.RESOLVED),
            CaseStatus.RESOLVED, List.of(CaseStatus.INVESTIGATING));
        for (var from : CaseStatus.values()) {
            for (var to : CaseStatus.values()) {
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isEqualTo(allowed.get(from).contains(to));
            }
        }
    }

    @Test
    void creation_validates_its_input() {
        var bad = new SupportCase.OpenData(CUSTOMER, CaseCategory.GENERAL, null, " ", "d", null, null, null, null, null, null, null, null, null, null, null, null,
            List.of(), List.of(), List.of(), null, null, false, "WEB", "k");
        assertThatThrownBy(() -> SupportCase.open("CASE-X", bad, CasePriority.LOW, List.of(), T0)).isInstanceOf(SupportDomainException.class);
    }
}
