package com.dsports.support.application.usecase;

import com.dsports.shared.application.TransactionRunner;
import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.SupportCase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Everything a customer can do with their OWN cases. A case that belongs to somebody else is reported as "not found", exactly
 * like one that does not exist, so nobody can learn whether another customer's case ID is real.
 */
public class CustomerCaseUseCase {

    public record CaseView(SupportCase supportCase, List<CaseMessage> messages, List<CaseEvent> events) {}

    private final SupportCaseRepository repository;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final Duration reopenWindow;

    public CustomerCaseUseCase(SupportCaseRepository repository, TransactionRunner transactions, Clock clock, Duration reopenWindow) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
        this.reopenWindow = reopenWindow;
    }

    public Flux<SupportCase> mine(UUID userId, boolean unresolvedOnly) {
        return repository.findByUser(userId).filter(c -> !unresolvedOnly || c.getStatus().isUnresolved());
    }

    /** {@code ref} is the case id (UUID) or the human-readable case number. */
    public Mono<CaseView> get(String ref, UUID userId) {
        return owned(ref, userId).flatMap(c -> Mono.zip(repository.messages(c.getId(), false).collectList(),
            repository.events(c.getId(), true).collectList()).map(t -> new CaseView(c, t.getT1(), t.getT2())));
    }

    public Mono<CaseView> addMessage(String ref, UUID userId, String body) {
        if (body == null || body.isBlank() || body.length() > 2000) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "A message of 1-2000 characters is required"));
        }
        return transactions.inTransaction(() -> owned(ref, userId).flatMap(c -> {
            var now = clock.instant();
            var from = c.getStatus();
            var changed = c.onCustomerMessage(now);
            return repository.save(c)
                .then(repository.addMessage(CaseMessage.of(c.getId(), CaseMessage.CUSTOMER, userId, false, body.trim(), now)))
                .then(repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.MESSAGE_ADDED, CaseMessage.CUSTOMER, userId, null, null, "Customer message", true, now)))
                .then(changed ? repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.STATUS_CHANGED, CaseMessage.CUSTOMER, userId, from.name(),
                    c.getStatus().name(), "Customer replied", true, now)) : Mono.<Void>empty())
                .thenReturn(c);
        })).flatMap(c -> get(c.getId().toString(), userId));
    }

    /** The customer asks for a human. This records a request; it does NOT mean anyone has accepted the case. */
    public Mono<CaseView> requestEscalation(String ref, UUID userId, String reason) {
        return transactions.inTransaction(() -> owned(ref, userId).flatMap(c -> {
            var now = clock.instant();
            var changed = c.requestEscalation(reason, now);
            if (!changed) {
                return Mono.just(c);
            }
            return repository.save(c)
                .then(repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.ESCALATION_REQUESTED, CaseMessage.CUSTOMER, userId, null, null,
                    "Human review requested: " + c.getEscalationReason(), true, now)))
                .thenReturn(c);
        })).flatMap(c -> get(c.getId().toString(), userId));
    }

    public Mono<CaseView> reopen(String ref, UUID userId, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 1000) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "A reason of 1-1000 characters is required to reopen a case"));
        }
        return transactions.inTransaction(() -> owned(ref, userId).flatMap(c -> {
            var now = clock.instant();
            c.reopen(false, reopenWindow, now);
            return repository.save(c)
                .then(repository.addMessage(CaseMessage.of(c.getId(), CaseMessage.CUSTOMER, userId, false, reason.trim(), now)))
                .then(repository.addEvent(CaseEvent.of(c.getId(), CaseEvent.REOPENED, CaseMessage.CUSTOMER, userId, CaseStatus.RESOLVED.name(),
                    CaseStatus.INVESTIGATING.name(), "Reopened by the customer", true, now)))
                .thenReturn(c);
        })).flatMap(c -> get(c.getId().toString(), userId));
    }

    public Duration reopenWindow() {
        return reopenWindow;
    }

    private Mono<SupportCase> owned(String ref, UUID userId) {
        return find(ref).filter(c -> c.isOwnedBy(userId)).switchIfEmpty(Mono.error(SupportDomainException.notFound()));
    }

    private Mono<SupportCase> find(String ref) {
        try {
            return repository.findById(UUID.fromString(ref));
        } catch (IllegalArgumentException notAUuid) {
            return repository.findByCaseNumber(ref.trim().toUpperCase());
        }
    }
}
