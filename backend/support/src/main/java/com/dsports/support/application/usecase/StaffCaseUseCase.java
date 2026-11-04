package com.dsports.support.application.usecase;

import com.dsports.shared.application.TransactionRunner;
import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.CasePriority;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.EvidenceItem;
import com.dsports.support.domain.model.ResolutionOutcome;
import com.dsports.support.domain.model.SupportCase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Operations for authorised support staff (SUPPORT_EXECUTIVE or ADMIN, enforced at the HTTP boundary). Every change is checked
 * against the lifecycle in {@link CaseStatus}, may carry the version the staff member was looking at (a stale view is rejected),
 * and is written to the append-only case history. Internal notes are stored with {@code internal = true} and never reach customers.
 */
public class StaffCaseUseCase {

    public record StaffView(SupportCase supportCase, List<CaseMessage> messages, List<CaseEvent> events) {}

    public record Page(List<SupportCase> items, long total, int page, int size) {}

    private final SupportCaseRepository repository;
    private final TransactionRunner transactions;
    private final Clock clock;

    public StaffCaseUseCase(SupportCaseRepository repository, TransactionRunner transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public Mono<Page> search(SupportCaseRepository.CaseFilter filter, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 100);
        return Mono.zip(repository.search(filter, p, s).collectList(), repository.count(filter)).map(t -> new Page(t.getT1(), t.getT2(), p, s));
    }

    public Mono<StaffView> get(String ref) {
        return find(ref).flatMap(this::view);
    }

    public Mono<StaffView> assign(String ref, UUID staffId, String queue, boolean takeIt, Integer expectedVersion) {
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            var wasEscalated = c.getEscalationState();
            var started = c.assign(queue, takeIt ? staffId : null, now);
            var events = new java.util.ArrayList<CaseEvent>();
            events.add(CaseEvent.of(c.getId(), CaseEvent.ASSIGNED, CaseMessage.STAFF, staffId, null, null,
                (takeIt ? "Taken by a support executive" : "Queue set to " + c.getAssignedQueue()), true, now));
            if (started != null) {
                events.add(CaseEvent.of(c.getId(), CaseEvent.STATUS_CHANGED, CaseMessage.STAFF, staffId, started.name(), c.getStatus().name(),
                    "Investigation started", true, now));
            }
            if (wasEscalated == com.dsports.support.domain.model.EscalationState.REQUESTED && takeIt) {
                events.add(CaseEvent.of(c.getId(), CaseEvent.ESCALATION_ACCEPTED, CaseMessage.STAFF, staffId, null, null,
                    "A support executive accepted the escalated case", true, now));
            }
            return Flux.fromIterable(events).concatMap(repository::addEvent).then();
        });
    }

    public Mono<StaffView> changePriority(String ref, UUID staffId, CasePriority target, String reason, Integer expectedVersion) {
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            var from = c.changePriority(target, reason, now);
            return repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.PRIORITY_CHANGED, CaseMessage.STAFF, staffId, null, null,
                "Priority " + from + " -> " + target + ": " + reason.trim(), false, now));
        });
    }

    public Mono<StaffView> addNote(String ref, UUID staffId, String body, Integer expectedVersion) {
        validateBody(body);
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            c.touch(now);
            return repository.addMessage(CaseMessage.of(c.getId(), CaseMessage.STAFF, staffId, true, body.trim(), now))
                .then(repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.NOTE_ADDED, CaseMessage.STAFF, staffId, null, null, "Internal note added", false, now)));
        });
    }

    public Mono<StaffView> reply(String ref, UUID staffId, String body, Integer expectedVersion) {
        validateBody(body);
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            c.touch(now);
            return repository.addMessage(CaseMessage.of(c.getId(), CaseMessage.STAFF, staffId, false, body.trim(), now))
                .then(repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.MESSAGE_ADDED, CaseMessage.STAFF, staffId, null, null, "Reply from support", true, now)));
        });
    }

    public Mono<StaffView> changeStatus(String ref, UUID staffId, CaseStatus target, String note, Integer expectedVersion) {
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            if (c.getStatus() == CaseStatus.RESOLVED && target == CaseStatus.INVESTIGATING) {
                c.reopen(true, Duration.ZERO, now);
                return repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.REOPENED, CaseMessage.STAFF, staffId, CaseStatus.RESOLVED.name(),
                    target.name(), note == null ? "Reopened by support" : note, true, now));
            }
            var from = c.changeStatus(target, now);
            return repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.STATUS_CHANGED, CaseMessage.STAFF, staffId, from.name(), target.name(), note, true, now));
        });
    }

    public Mono<StaffView> resolve(String ref, UUID staffId, ResolutionOutcome outcome, String note, List<String> evidenceRefs, Integer expectedVersion) {
        return change(ref, expectedVersion, c -> {
            var now = clock.instant();
            var from = c.resolve(outcome, note, staffId, now);
            if (evidenceRefs != null) {
                evidenceRefs.stream().filter(r -> r != null && !r.isBlank()).limit(10)
                    .forEach(r -> c.addEvidence(new EvidenceItem("STAFF_FINDING", r, "Supporting reference recorded at resolution", EvidenceItem.STAFF)));
            }
            return repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.RESOLVED, CaseMessage.STAFF, staffId, from.name(), CaseStatus.RESOLVED.name(),
                "Outcome " + outcome + ": " + note.trim(), true, now));
        });
    }

    public Mono<SupportCaseRepository.Analytics> analytics() {
        return repository.analytics();
    }

    public Mono<SupportCaseRepository.Report> report(java.time.Instant from, java.time.Instant to, String zone) {
        return repository.report(from, to, zone);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private Mono<StaffView> change(String ref, Integer expectedVersion, java.util.function.Function<SupportCase, Mono<Void>> mutation) {
        return transactions.inTransaction(() -> find(ref).flatMap(c -> {
            if (expectedVersion != null && expectedVersion != c.getVersion()) {
                return Mono.<SupportCase>error(new SupportDomainException(SupportErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                    "Case " + c.getCaseNumber() + " was changed by someone else. Reload and retry."));
            }
            return Mono.defer(() -> mutation.apply(c)).then(Mono.defer(() -> repository.save(c))).thenReturn(c);   // save must see the mutated aggregate
        })).flatMap(this::view);
    }

    private Mono<StaffView> view(SupportCase c) {
        return repository.findById(c.getId()).flatMap(fresh -> Mono.zip(repository.messages(fresh.getId(), true).collectList(),
            repository.events(fresh.getId(), false).collectList()).map(t -> new StaffView(fresh, t.getT1(), t.getT2())));
    }

    private Mono<SupportCase> find(String ref) {
        Mono<SupportCase> found;
        try {
            found = repository.findById(UUID.fromString(ref));
        } catch (IllegalArgumentException notAUuid) {
            found = repository.findByCaseNumber(ref.trim().toUpperCase());
        }
        return found.switchIfEmpty(Mono.error(SupportDomainException.notFound()));
    }

    private static void validateBody(String body) {
        if (body == null || body.isBlank() || body.length() > 2000) {
            throw new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "A message of 1-2000 characters is required");
        }
    }
}
