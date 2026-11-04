package com.dsports.support.application.port;

import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.SupportCase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence port of the support module. Everything staff-side is aggregated or filtered in the database, never in the browser. */
public interface SupportCaseRepository {

    /** Staff search filter; filters are combined with AND. {@code createdTo} is exclusive. */
    record CaseFilter(String status, String category, String priority, String escalation, Boolean unassigned, UUID assignedTo,
                      String queue, String text, Instant createdFrom, Instant createdTo, UUID customerId) {

        public CaseFilter(String status, String category, String priority, String escalation, Boolean unassigned, UUID assignedTo,
                          String queue, String text) {
            this(status, category, priority, escalation, unassigned, assignedTo, queue, text, null, null, null);
        }
    }

    record Analytics(long totalCases, long unresolvedCases, Map<String, Long> byStatus, Map<String, Long> byCategory,
                     Map<String, Long> byPriority, long escalationRequestedNotAccepted, long escalationAccepted,
                     Map<String, Long> resolvedByOutcome, long resolvedByHuman, long reopenedCases, long casesWithRepeatContact,
                     Double averageUnresolvedAgeHours, Double oldestUnresolvedAgeHours, long createdByAssistant, long createdByWeb,
                     long unassignedUnresolved) {}

    /** One calendar day (in the business zone): cases created and cases resolved on it. */
    record DayPoint(LocalDate date, long created, long resolved) {}

    /**
     * Period report, aggregated in the database. Period metrics count events inside [from, to); the {@code ...Now} metrics describe the current state
     * regardless of the period. {@code createdInPeriodStillUnresolved} is the cohort of the period's tickets that are still unresolved today (a different
     * number from {@code unresolvedNow}).
     */
    record Report(Instant from, Instant to, String zone, long createdInPeriod, long resolvedInPeriod, long reopenedInPeriod, long escalatedInPeriod,
                  long openNow, long inProgressNow, long unresolvedNow, long unassignedNow, long escalationPendingNow, long createdInPeriodStillUnresolved,
                  Map<String, Long> createdByCategory, Map<String, Long> createdByPriority, List<DayPoint> daily, Double oldestUnresolvedAgeHours) {}

    Mono<SupportCase> findById(UUID id);

    Mono<SupportCase> findByCaseNumber(String caseNumber);

    Flux<SupportCase> findByUser(UUID userId);

    Mono<SupportCase> findByUserAndKey(UUID userId, String idempotencyKey);

    Mono<SupportCase> findUnresolvedAbout(UUID userId, UUID orderId, CaseCategory category);

    Mono<Integer> countCasesAboutOrder(UUID userId, UUID orderId);

    Mono<Long> nextSequence();

    /** Serialises concurrent creations for one (customer, idempotency key) so a double submit can never open two tickets. */
    Mono<Void> lockCreation(UUID userId, String idempotencyKey);

    Mono<Void> save(SupportCase supportCase);

    Mono<Void> addMessage(CaseMessage message);

    Flux<CaseMessage> messages(UUID caseId, boolean includeInternal);

    Mono<Void> addEvent(CaseEvent event);

    Flux<CaseEvent> events(UUID caseId, boolean includeInternal);

    Flux<SupportCase> search(CaseFilter filter, int page, int size);

    Mono<Long> count(CaseFilter filter);

    Mono<Analytics> analytics();

    Mono<Report> report(Instant from, Instant to, String zone);
}
