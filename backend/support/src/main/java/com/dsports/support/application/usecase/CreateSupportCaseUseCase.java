package com.dsports.support.application.usecase;

import com.dsports.shared.application.TransactionRunner;
import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.application.port.SupportContextPort;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.EvidenceItem;
import com.dsports.support.domain.model.SupportCase;
import com.dsports.support.domain.policy.PriorityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Opens a support case for the authenticated customer.
 * <ul>
 *   <li>Idempotent per (customer, Idempotency-Key): a retry returns the original case; reusing the key for a different request is rejected.</li>
 *   <li>Related records (order, product, payment, return) must belong to the customer. The backend then records its OWN snapshot of
 *       the order, shipment and payments as BACKEND_VERIFIED evidence, separate from whatever the caller submitted.</li>
 *   <li>If the customer already has an unresolved case of the same category about the same order, no second case is created:
 *       the existing one is returned (outcome ALREADY_OPEN).</li>
 * </ul>
 */
public class CreateSupportCaseUseCase {
    private static final Logger log = LoggerFactory.getLogger(CreateSupportCaseUseCase.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public enum Outcome { CREATED, REPLAYED, ALREADY_OPEN }

    public record Command(UUID userId, String idempotencyKey, CaseCategory category, String subcategory, String subject, String description,
                          UUID orderId, UUID productId, UUID paymentId, UUID returnId, String refundReference, boolean safetyRelated,
                          String investigationSummary, List<EvidenceItem> submittedEvidence, List<String> policyCitations,
                          List<String> missingInformation, String resolutionRecommendation, String escalationReason, String source) {}

    public record Result(Outcome outcome, SupportCase supportCase) {}

    private final SupportCaseRepository repository;
    private final SupportContextPort context;
    private final TransactionRunner transactions;
    private final Clock clock;
    private final BigDecimal highValueThreshold;

    public CreateSupportCaseUseCase(SupportCaseRepository repository, SupportContextPort context, TransactionRunner transactions,
                                    Clock clock, BigDecimal highValueThreshold) {
        this.repository = repository;
        this.context = context;
        this.transactions = transactions;
        this.clock = clock;
        this.highValueThreshold = highValueThreshold;
    }

    public Mono<Result> execute(Command c) {
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            return Mono.error(new SupportDomainException(SupportErrorCode.VALIDATION_ERROR, "An Idempotency-Key header is required to create a case"));
        }
        return transactions.inTransaction(() -> repository.lockCreation(c.userId(), c.idempotencyKey())
            .then(Mono.defer(() -> repository.findByUserAndKey(c.userId(), c.idempotencyKey())))
            .flatMap(existing -> sameRequest(existing, c)
                ? Mono.just(new Result(Outcome.REPLAYED, existing))
                : Mono.<Result>error(new SupportDomainException(SupportErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used for a different case")))
            .switchIfEmpty(Mono.defer(() -> create(c))));
    }

    private Mono<Result> create(Command c) {
        var order = c.orderId() == null ? Mono.<SupportContextPort.OrderFacts>empty()
            : context.orderOfCustomer(c.orderId(), c.userId())
                .switchIfEmpty(Mono.error(related("order")));
        return order.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
            .flatMap(facts -> verifyOthers(c).thenReturn(facts))
            .flatMap(facts -> {
                if (c.productId() != null && facts.isPresent() && !facts.get().productNames().containsKey(c.productId())) {
                    return Mono.<Result>error(related("product in that order"));
                }
                var existingOpen = c.orderId() == null ? Mono.<SupportCase>empty() : repository.findUnresolvedAbout(c.userId(), c.orderId(), c.category());
                return existingOpen.map(open -> new Result(Outcome.ALREADY_OPEN, open))
                    .switchIfEmpty(Mono.defer(() -> persist(c, facts.orElse(null))));
            });
    }

    private Mono<Void> verifyOthers(Command c) {
        Mono<Void> payment = c.paymentId() == null ? Mono.empty()
            : context.paymentBelongsTo(c.paymentId(), c.userId()).filter(Boolean::booleanValue).switchIfEmpty(Mono.error(related("payment"))).then();
        Mono<Void> ret = c.returnId() == null ? Mono.empty()
            : context.returnBelongsTo(c.returnId(), c.userId()).filter(Boolean::booleanValue).switchIfEmpty(Mono.error(related("return"))).then();
        return payment.then(ret);
    }

    private Mono<Result> persist(Command c, SupportContextPort.OrderFacts order) {
        var now = clock.instant();
        var repeat = c.orderId() == null ? Mono.just(0) : repository.countCasesAboutOrder(c.userId(), c.orderId());
        return Mono.zip(repository.nextSequence(), repeat).flatMap(t -> {
            var number = "CASE-" + DAY.format(now) + "-" + String.format("%06d", t.getT1());
            var priority = PriorityPolicy.evaluate(c.category(), c.safetyRelated(), order == null ? null : order.grandTotal(), t.getT2(),
                order == null ? 0 : order.successfulPayments(), highValueThreshold);
            var evidence = new ArrayList<EvidenceItem>(backendEvidence(order));
            var submittedSource = "AI_ASSISTANT".equals(c.source()) ? EvidenceItem.AI_INVESTIGATION : EvidenceItem.CUSTOMER;
            for (var e : c.submittedEvidence()) {
                evidence.add(new EvidenceItem(e.kind(), e.ref(), e.summary(), submittedSource));   // a caller can never claim BACKEND_VERIFIED
            }
            var product = c.productId() == null || order == null ? null : order.productNames().get(c.productId());
            var data = new SupportCase.OpenData(c.userId(), c.category(), c.subcategory(), c.subject(), c.description(), c.orderId(),
                order == null ? null : order.orderNumber(), c.productId(), product, c.paymentId(), c.returnId(), c.refundReference(),
                order == null ? null : order.status(), order == null ? null : order.shipmentStatus(), order == null ? null : order.grandTotal(),
                order == null ? null : order.currency(), c.investigationSummary(), evidence, nz(c.policyCitations()), nz(c.missingInformation()),
                c.resolutionRecommendation(), c.escalationReason(), c.safetyRelated(), "AI_ASSISTANT".equals(c.source()) ? "AI_ASSISTANT" : "WEB",
                c.idempotencyKey());
            var supportCase = SupportCase.open(number, data, priority.priority(), priority.reasons(), now);
            var actor = CaseMessage.CUSTOMER;
            return repository.save(supportCase)
                .then(repository.addEvent(CaseEvent.of(supportCase.getId(), CaseEvent.CREATED, actor, c.userId(), null, supportCase.getStatus().name(),
                    "Case opened via " + supportCase.getSource(), true, now)))
                .then(repository.addMessage(CaseMessage.of(supportCase.getId(), actor, c.userId(), false, c.description(), now)))
                .then(supportCase.getEscalationReason() == null ? Mono.<Void>empty()
                    : repository.addEvent(CaseEvent.of(supportCase.getId(), CaseEvent.ESCALATION_REQUESTED, actor, c.userId(), null, null,
                        "Human review requested: " + supportCase.getEscalationReason(), true, now)))
                .doOnSuccess(v -> log.info("Support case {} opened ({}, {}, priority {})", number, c.category(), supportCase.getSource(), priority.priority()))
                .thenReturn(new Result(Outcome.CREATED, supportCase));
        });
    }

    private static List<EvidenceItem> backendEvidence(SupportContextPort.OrderFacts o) {
        if (o == null) {
            return List.of();
        }
        var out = new ArrayList<EvidenceItem>();
        out.add(new EvidenceItem("ORDER", o.orderNumber(), "Order " + o.orderNumber() + " has status " + o.status() + " (placed " + o.placedAt() + ")",
            EvidenceItem.BACKEND_VERIFIED));
        out.add(new EvidenceItem("SHIPMENT", o.orderNumber(), o.shipmentStatus() == null ? "No shipment record exists for this order"
            : "Shipment status is " + o.shipmentStatus() + (o.deliveredAt() == null ? ", no delivery timestamp recorded" : ", delivered at " + o.deliveredAt()),
            EvidenceItem.BACKEND_VERIFIED));
        out.add(new EvidenceItem("PAYMENT", o.orderNumber(), o.successfulPayments() + " successful payment(s) out of " + o.paymentAttempts()
            + " attempt(s) recorded for this order", EvidenceItem.BACKEND_VERIFIED));
        return out;
    }

    private static boolean sameRequest(SupportCase existing, Command c) {
        return existing.getCategory() == c.category() && Objects.equals(existing.getOrderId(), c.orderId())
            && Objects.equals(existing.getSubject(), c.subject() == null ? null : c.subject().trim());
    }

    private static <T> List<T> nz(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static SupportDomainException related(String what) {
        return new SupportDomainException(SupportErrorCode.RELATED_RECORD_NOT_FOUND, "We could not find that " + what + " on your account");
    }
}
