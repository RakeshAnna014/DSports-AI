package com.dsports.support.infrastructure.persistence;

import com.dsports.shared.application.TransactionRunner;
import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.application.port.SupportContextPort;
import com.dsports.support.application.usecase.CreateSupportCaseUseCase;
import com.dsports.support.application.usecase.CustomerCaseUseCase;
import com.dsports.support.application.usecase.StaffCaseUseCase;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CasePriority;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.EscalationState;
import com.dsports.support.domain.model.EvidenceItem;
import com.dsports.support.domain.model.ResolutionOutcome;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Support cases against a real PostgreSQL: persistence, idempotency under concurrency, ownership (another customer's case is
 * indistinguishable from a missing one), hidden internal notes, the lifecycle, optimistic locking and the database constraints.
 */
@Tag("integration")
class SupportCaseIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DatabaseClient db;
    private static SupportCaseR2dbcRepositoryAdapter repository;
    private static CreateSupportCaseUseCase create;
    private static CustomerCaseUseCase customer;
    private static StaffCaseUseCase staff;
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID STAFF = UUID.randomUUID();
    private static final UUID ALICE_ORDER = UUID.randomUUID();
    private static final UUID ALICE_ORDER_2 = UUID.randomUUID();
    private static final UUID ALICE_BIG_ORDER = UUID.randomUUID();
    private static final UUID BOB_ORDER = UUID.randomUUID();
    private static final UUID PRIO_BIG = UUID.randomUUID();
    private static final UUID PRIO_DUP = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ALICE_PAYMENT = UUID.randomUUID();

    private static SupportContextPort.OrderFacts order(UUID id, String number, BigDecimal total, int successfulPayments) {
        return new SupportContextPort.OrderFacts(id, number, "DELIVERED", total, "INR", Instant.parse("2026-10-01T10:00:00Z"), "DELIVERED",
            null, successfulPayments, successfulPayments, Map.of(PRODUCT, "MRF Cricket Bat - Virgin"));
    }

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        ConnectionFactory cf = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost()
            + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(cf);
        var operator = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        TransactionRunner transactions = new TransactionRunner() {
            @Override
            public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
                return Mono.defer(work).as(operator::transactional);
            }
        };
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(cf));
        repository = new SupportCaseR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcSupportCaseRepository.class), db);
        SupportContextPort context = new SupportContextPort() {
            private final Map<UUID, SupportContextPort.OrderFacts> orders = Map.of(
                ALICE_ORDER, order(ALICE_ORDER, "ORD-20261001-000001", new BigDecimal("2500.00"), 1),
                ALICE_ORDER_2, order(ALICE_ORDER_2, "ORD-20261001-000002", new BigDecimal("1800.00"), 2),
                ALICE_BIG_ORDER, order(ALICE_BIG_ORDER, "ORD-20261001-000003", new BigDecimal("15000.00"), 1),
                BOB_ORDER, order(BOB_ORDER, "ORD-20261001-000009", new BigDecimal("900.00"), 1),
                PRIO_BIG, order(PRIO_BIG, "ORD-20261001-000004", new BigDecimal("15000.00"), 1),
                PRIO_DUP, order(PRIO_DUP, "ORD-20261001-000005", new BigDecimal("500.00"), 2));

            @Override
            public Mono<OrderFacts> orderOfCustomer(UUID orderId, UUID userId) {
                boolean mine = (userId.equals(ALICE) && orderId.equals(ALICE_ORDER)) || (userId.equals(ALICE) && orderId.equals(ALICE_ORDER_2))
                    || (userId.equals(ALICE) && orderId.equals(ALICE_BIG_ORDER)) || (userId.equals(BOB) && orderId.equals(BOB_ORDER))
                    || (userId.equals(ALICE) && (orderId.equals(PRIO_BIG) || orderId.equals(PRIO_DUP)));
                return mine ? Mono.just(orders.get(orderId)) : Mono.empty();
            }

            @Override
            public Mono<Boolean> paymentBelongsTo(UUID paymentId, UUID userId) {
                return Mono.just(paymentId.equals(ALICE_PAYMENT) && userId.equals(ALICE));
            }

            @Override
            public Mono<Boolean> returnBelongsTo(UUID returnId, UUID userId) {
                return Mono.just(false);
            }
        };
        var clock = Clock.system(ZoneOffset.UTC);
        create = new CreateSupportCaseUseCase(repository, context, transactions, clock, new BigDecimal("10000"));
        customer = new CustomerCaseUseCase(repository, transactions, clock, Duration.ofDays(7));
        staff = new StaffCaseUseCase(repository, transactions, clock);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static CreateSupportCaseUseCase.Command cmd(UUID user, String key, CaseCategory category, UUID orderId, String subject) {
        return new CreateSupportCaseUseCase.Command(user, key, category, null, subject, "It says delivered but nothing arrived", orderId, null, null, null, null, false,
            "Shipment is DELIVERED but no delivery timestamp is recorded.", List.of(new EvidenceItem("SHIPMENT", "TRK1", "Status DELIVERED", "BACKEND_VERIFIED")),
            List.of("Missing delivery procedure (sample)"), List.of("Delivery photo"), "Open a case for investigation", null, "AI_ASSISTANT");
    }

    private static CreateSupportCaseUseCase.Result open(UUID user, String key, CaseCategory category, UUID orderId, String subject) {
        return create.execute(cmd(user, key, category, orderId, subject)).block();
    }

    // ------------------------------------------------------------------------------------------------ creation and idempotency

    @Test
    void a_case_is_persisted_with_backend_verified_facts_separate_from_what_the_caller_submitted() {
        var r = open(ALICE, "k-create-1", CaseCategory.MISSING_DELIVERY, ALICE_ORDER, "Missing delivery 1");
        assertThat(r.outcome()).isEqualTo(CreateSupportCaseUseCase.Outcome.CREATED);
        var c = r.supportCase();
        assertThat(c.getCaseNumber()).matches("CASE-\\d{8}-\\d{6}");
        assertThat(c.getOrderNumber()).isEqualTo("ORD-20261001-000001");
        assertThat(c.getOrderStatusAtCreation()).isEqualTo("DELIVERED");
        var stored = repository.findById(c.getId()).block();
        assertThat(stored.getEvidence()).filteredOn(e -> EvidenceItem.BACKEND_VERIFIED.equals(e.source())).extracting(EvidenceItem::kind).contains("ORDER", "SHIPMENT", "PAYMENT");
        // the caller's own item arrived labelled as the assistant's, even though it CLAIMED to be backend verified
        assertThat(stored.getEvidence()).filteredOn(e -> "TRK1".equals(e.ref())).extracting(EvidenceItem::source).containsExactly(EvidenceItem.AI_INVESTIGATION);
        assertThat(stored.getInvestigationSummary()).contains("no delivery timestamp");
        assertThat(stored.getPolicyCitations()).containsExactly("Missing delivery procedure (sample)");
    }

    @Test
    void retrying_with_the_same_key_returns_the_same_case_and_a_different_request_with_that_key_is_refused() {
        var first = open(ALICE, "k-idem-1", CaseCategory.DELAYED_DELIVERY, ALICE_ORDER_2, "Delay");
        var again = open(ALICE, "k-idem-1", CaseCategory.DELAYED_DELIVERY, ALICE_ORDER_2, "Delay");
        assertThat(again.outcome()).isEqualTo(CreateSupportCaseUseCase.Outcome.REPLAYED);
        assertThat(again.supportCase().getId()).isEqualTo(first.supportCase().getId());
        assertThatThrownBy(() -> open(ALICE, "k-idem-1", CaseCategory.PAYMENT_ISSUE, ALICE_ORDER_2, "Something else"))
            .isInstanceOf(SupportDomainException.class).extracting("errorCode").isEqualTo(SupportErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void eight_concurrent_submissions_of_one_key_create_exactly_one_case() {
        var results = Flux.range(0, 8).flatMap(i -> create.execute(cmd(BOB, "k-race", CaseCategory.MISSING_DELIVERY, BOB_ORDER, "Race"))
            .subscribeOn(Schedulers.parallel())).collectList().block();
        assertThat(results).hasSize(8);
        assertThat(results.stream().map(r -> r.supportCase().getId()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> r.outcome() == CreateSupportCaseUseCase.Outcome.CREATED)).hasSize(1);
        var rows = db.sql("SELECT COUNT(*) AS n FROM support_cases WHERE user_id = :u AND idempotency_key = 'k-race'").bind("u", BOB)
            .map((row, m) -> row.get("n", Long.class)).one().block();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void a_second_unresolved_case_of_the_same_category_about_the_same_order_is_not_created() {
        var first = open(ALICE, "k-dup-1", CaseCategory.PAYMENT_ISSUE, ALICE_ORDER_2, "Payment deducted");
        var second = open(ALICE, "k-dup-2", CaseCategory.PAYMENT_ISSUE, ALICE_ORDER_2, "Payment deducted again");
        assertThat(second.outcome()).isEqualTo(CreateSupportCaseUseCase.Outcome.ALREADY_OPEN);
        assertThat(second.supportCase().getId()).isEqualTo(first.supportCase().getId());
    }

    @Test
    void related_records_of_somebody_else_cannot_be_attached() {
        assertThatThrownBy(() -> open(ALICE, "k-foreign", CaseCategory.MISSING_DELIVERY, BOB_ORDER, "Not mine"))
            .isInstanceOf(SupportDomainException.class).extracting("errorCode").isEqualTo(SupportErrorCode.RELATED_RECORD_NOT_FOUND);
        var foreignPayment = new CreateSupportCaseUseCase.Command(BOB, "k-foreign-pay", CaseCategory.PAYMENT_ISSUE, null, "Pay", "x", null, null, ALICE_PAYMENT, null, null,
            false, null, List.of(), List.of(), List.of(), null, null, "WEB");
        assertThatThrownBy(() -> create.execute(foreignPayment).block()).isInstanceOf(SupportDomainException.class);
    }

    @Test
    void priority_follows_the_documented_rules_and_records_its_reasons() {
        var big = open(ALICE, "k-prio-1", CaseCategory.MISSING_DELIVERY, PRIO_BIG, "Expensive missing parcel").supportCase();
        assertThat(big.getPriority()).isEqualTo(CasePriority.HIGH);
        assertThat(big.getPriorityReasons()).anyMatch(r -> r.contains("high-value"));
        var repeat = open(ALICE, "k-prio-2", CaseCategory.DAMAGED_PRODUCT, PRIO_BIG, "It was also damaged").supportCase();
        assertThat(repeat.getPriorityReasons()).anyMatch(r -> r.contains("Repeat contact"));
        var dup = open(ALICE, "k-prio-3", CaseCategory.DUPLICATE_PAYMENT, PRIO_DUP, "Charged twice").supportCase();   // the backend counted 2 successful payments
        assertThat(dup.getPriority()).isEqualTo(CasePriority.HIGH);
        assertThat(dup.getPriorityReasons()).anyMatch(r -> r.contains("2 successful payments"));
    }

    // ------------------------------------------------------------------------------------------------ ownership and hidden notes

    @Test
    void another_customers_case_is_indistinguishable_from_a_missing_one() {
        var alices = open(ALICE, "k-own-1", CaseCategory.RETURN_ISSUE, ALICE_ORDER, "Return rejected").supportCase();
        for (var ref : List.of(alices.getId().toString(), alices.getCaseNumber(), UUID.randomUUID().toString(), "CASE-20990101-999999")) {
            assertThatThrownBy(() -> customer.get(ref, BOB).block()).isInstanceOf(SupportDomainException.class)
                .hasMessage("Support case not found").extracting("errorCode").isEqualTo(SupportErrorCode.CASE_NOT_FOUND);
        }
        assertThatThrownBy(() -> customer.addMessage(alices.getId().toString(), BOB, "hello").block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.CASE_NOT_FOUND);
        assertThatThrownBy(() -> customer.requestEscalation(alices.getId().toString(), BOB, "mine now").block()).isInstanceOf(SupportDomainException.class);
        assertThat(customer.mine(BOB, false).collectList().block()).noneMatch(c -> c.getId().equals(alices.getId()));
        assertThat(customer.get(alices.getCaseNumber(), ALICE).block().supportCase().getId()).isEqualTo(alices.getId());
    }

    @Test
    void internal_notes_never_reach_the_customer_and_staff_see_them() {
        var c = open(ALICE, "k-note-1", CaseCategory.PRODUCT_COMPLAINT, ALICE_ORDER, "Spec mismatch").supportCase();
        staff.addNote(c.getId().toString(), STAFF, "Customer has 3 earlier complaints - check for abuse", null).block();
        staff.reply(c.getId().toString(), STAFF, "We are looking into the specification.", null).block();
        var seenByCustomer = customer.get(c.getId().toString(), ALICE).block();
        assertThat(seenByCustomer.messages()).extracting(m -> m.body()).noneMatch(b -> b.contains("abuse")).anyMatch(b -> b.contains("looking into"));
        assertThat(seenByCustomer.events()).extracting(CaseEvent::eventType).doesNotContain(CaseEvent.NOTE_ADDED);
        var seenByStaff = staff.get(c.getId().toString()).block();
        assertThat(seenByStaff.messages()).anyMatch(m -> m.internal() && m.body().contains("abuse"));
        assertThat(seenByStaff.events()).extracting(CaseEvent::eventType).contains(CaseEvent.NOTE_ADDED);
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    @Test
    void the_full_lifecycle_is_audited_and_invalid_moves_are_rejected() {
        var c = open(ALICE, "k-life-1", CaseCategory.REFUND_ISSUE, ALICE_ORDER, "Refund late").supportCase();
        var id = c.getId().toString();
        var taken = staff.assign(id, STAFF, "REFUNDS", true, null).block();
        assertThat(taken.supportCase().getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThatThrownBy(() -> staff.changeStatus(id, STAFF, CaseStatus.OPEN, null, null).block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.INVALID_STATUS_TRANSITION);
        staff.changeStatus(id, STAFF, CaseStatus.WAITING_FOR_CUSTOMER, "Need the bank reference", null).block();
        var replied = customer.addMessage(id, ALICE, "The reference is 12345").block();
        assertThat(replied.supportCase().getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        var resolved = staff.resolve(id, STAFF, ResolutionOutcome.REFUND_PROCESSED_VIA_PAYMENT_FLOW, "Refund was processed through the payment flow", List.of("REF-1"), null).block();
        assertThat(resolved.supportCase().getStatus()).isEqualTo(CaseStatus.RESOLVED);
        assertThat(resolved.supportCase().getResolvedBy()).isEqualTo(STAFF);
        assertThatThrownBy(() -> customer.addMessage(id, ALICE, "hello?").block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.CASE_RESOLVED);
        var reopened = customer.reopen(id, ALICE, "Money is still missing").block();
        assertThat(reopened.supportCase().getStatus()).isEqualTo(CaseStatus.INVESTIGATING);
        assertThat(reopened.supportCase().getReopenCount()).isEqualTo(1);
        var types = staff.get(id).block().events().stream().map(CaseEvent::eventType).toList();
        assertThat(types).containsSubsequence(CaseEvent.CREATED, CaseEvent.ASSIGNED, CaseEvent.STATUS_CHANGED, CaseEvent.MESSAGE_ADDED, CaseEvent.RESOLVED, CaseEvent.REOPENED);
    }

    @Test
    void a_customer_can_ask_for_a_human_but_only_a_staff_member_taking_the_case_accepts_it() {
        var c = open(ALICE, "k-esc-1", CaseCategory.RETURN_ISSUE, null, "Return rejected").supportCase();
        var requested = customer.requestEscalation(c.getId().toString(), ALICE, "I disagree with the decision").block();
        assertThat(requested.supportCase().getEscalationState()).isEqualTo(EscalationState.REQUESTED);
        assertThat(customer.requestEscalation(c.getId().toString(), ALICE, "again").block().supportCase().getEscalationState()).isEqualTo(EscalationState.REQUESTED);
        var accepted = staff.assign(c.getId().toString(), STAFF, null, true, null).block();
        assertThat(accepted.supportCase().getEscalationState()).isEqualTo(EscalationState.ACCEPTED);
        assertThat(accepted.events()).extracting(CaseEvent::eventType).contains(CaseEvent.ESCALATION_REQUESTED, CaseEvent.ESCALATION_ACCEPTED);
    }

    @Test
    void staff_priority_change_is_audited_privately_and_the_new_categories_and_date_filters_work() {
        var c = open(ALICE, "k-staffprio-1", CaseCategory.TECHNICAL_ISSUE, null, "PRIOTEST checkout page error").supportCase();
        assertThat(c.getPriority()).isEqualTo(CasePriority.LOW);
        var changed = staff.changePriority(c.getId().toString(), STAFF, CasePriority.HIGH, "Blocks every purchase", null).block();
        assertThat(changed.supportCase().getPriority()).isEqualTo(CasePriority.HIGH);
        assertThat(changed.events()).anyMatch(e -> CaseEvent.PRIORITY_CHANGED.equals(e.eventType()) && !e.customerVisible());
        assertThat(customer.get(c.getId().toString(), ALICE).block().events()).extracting(CaseEvent::eventType).doesNotContain(CaseEvent.PRIORITY_CHANGED);

        var now = Instant.now();
        var today = new SupportCaseRepository.CaseFilter(null, "TECHNICAL_ISSUE", "HIGH", null, null, null, null, "priotest",
            now.minusSeconds(3600), now.plusSeconds(3600), ALICE);
        assertThat(staff.search(today, 0, 10).block().total()).isEqualTo(1);
        var yesterday = new SupportCaseRepository.CaseFilter(null, null, null, null, null, null, null, "priotest",
            now.minusSeconds(7200), now.minusSeconds(3600), null);
        assertThat(staff.search(yesterday, 0, 10).block().total()).isZero();
        var otherCustomer = new SupportCaseRepository.CaseFilter(null, null, null, null, null, null, null, "priotest", null, null, BOB);
        assertThat(staff.search(otherCustomer, 0, 10).block().total()).isZero();
        assertThat(staff.search(new SupportCaseRepository.CaseFilter(null, "ORDER_ISSUE", null, null, null, null, null, null), 0, 10).block()).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------ period report

    private static void rawCase(String key, String created, String status, String category, String priority, String resolvedAt) {
        var spec = db.sql("INSERT INTO support_cases (id, case_number, user_id, category, priority, status, subject, description, idempotency_key, created_at, updated_at, "
                + "resolution_outcome, resolved_at, source) VALUES (:id, :n, :u, :cat, :pri, :st, :subj, 'REPORTTEST description', :k, CAST(:c AS timestamptz), CAST(:c AS timestamptz), "
                + ":outcome, CAST(:r AS timestamptz), 'WEB')")
            .bind("id", UUID.randomUUID()).bind("n", "CASE-RPT-" + key).bind("u", ALICE).bind("cat", category).bind("pri", priority).bind("st", status)
            .bind("subj", "REPORTTEST " + key).bind("k", "rpt-" + key).bind("c", created);
        spec = status.equals("RESOLVED") ? spec.bind("outcome", "ANSWERED") : spec.bindNull("outcome", String.class);
        spec = resolvedAt == null ? spec.bindNull("r", String.class) : spec.bind("r", resolvedAt);
        spec.fetch().rowsUpdated().block();
    }

    private static void rawEvent(String caseKey, String type, String at) {
        db.sql("INSERT INTO support_case_events (id, case_id, event_type, actor_type, created_at) SELECT :id, id, :t, 'STAFF', CAST(:a AS timestamptz) FROM support_cases WHERE idempotency_key = :k")
            .bind("id", UUID.randomUUID()).bind("t", type).bind("a", at).bind("k", "rpt-" + caseKey).fetch().rowsUpdated().block();
    }

    @Test
    void the_period_report_counts_each_metric_in_the_business_zone_with_an_exclusive_end_and_reconciles_with_the_list() {
        // Business day 2026-03-10 in Asia/Kolkata (UTC+5:30) is [2026-03-09T18:30Z, 2026-03-10T18:30Z).
        rawCase("A", "2026-03-10T10:00:00Z", "OPEN", "PAYMENT_ISSUE", "MEDIUM", null);                         // inside
        rawCase("B", "2026-03-09T19:00:00Z", "RESOLVED", "GENERAL", "LOW", "2026-03-10T05:00:00Z");         // inside in IST (00:30 on the 10th); the 9th in UTC
        rawCase("C", "2026-03-09T18:00:00Z", "INVESTIGATING", "PAYMENT_ISSUE", "HIGH", null);               // 23:30 on the 9th in IST: outside
        rawCase("D", "2026-03-10T18:29:59Z", "RESOLVED", "GENERAL", "LOW", "2026-03-11T02:00:00Z");         // inside, but resolved the next IST day
        rawCase("E", "2026-03-10T18:30:00Z", "OPEN", "GENERAL", "LOW", null);                                // exactly the exclusive end: outside
        rawEvent("A", CaseEvent.REOPENED, "2026-03-10T11:00:00Z");
        rawEvent("B", CaseEvent.ESCALATION_REQUESTED, "2026-03-10T06:00:00Z");
        rawEvent("C", CaseEvent.REOPENED, "2026-03-09T18:29:59Z");                                           // one second before the period starts: not counted

        var from = Instant.parse("2026-03-09T18:30:00Z");
        var to = Instant.parse("2026-03-10T18:30:00Z");
        var ist = repository.report(from, to, "Asia/Kolkata").block();
        assertThat(ist.createdInPeriod()).isEqualTo(3);                                                      // A, B, D
        assertThat(ist.resolvedInPeriod()).isEqualTo(1);                                                     // B only: D was resolved on the 11th
        assertThat(ist.reopenedInPeriod()).isEqualTo(1);
        assertThat(ist.escalatedInPeriod()).isEqualTo(1);
        assertThat(ist.createdInPeriodStillUnresolved()).isEqualTo(1);                                       // A: B and D are resolved
        assertThat(ist.createdByCategory()).containsEntry("GENERAL", 2L).containsEntry("PAYMENT_ISSUE", 1L);
        assertThat(ist.createdByCategory().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(ist.createdInPeriod());   // the breakdown reconciles with the total
        assertThat(ist.createdByPriority().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(ist.createdInPeriod());
        assertThat(ist.daily()).hasSize(1);
        assertThat(ist.daily().get(0).date()).isEqualTo(java.time.LocalDate.parse("2026-03-10"));
        assertThat(ist.daily().get(0).created()).isEqualTo(3);
        assertThat(ist.daily().get(0).resolved()).isEqualTo(1);
        assertThat(ist.unresolvedNow()).isEqualTo(ist.openNow() + ist.inProgressNow());                         // the "now" metrics are consistent with each other

        var filter = new SupportCaseRepository.CaseFilter(null, null, null, null, null, null, null, "reporttest", from, to, null);
        assertThat(staff.search(filter, 0, 100).block().total()).isEqualTo(ist.createdInPeriod());          // the list filter and the report agree

        var utc = repository.report(Instant.parse("2026-03-10T00:00:00Z"), Instant.parse("2026-03-11T00:00:00Z"), "UTC").block();
        assertThat(utc.createdInPeriod()).isEqualTo(3);                                                      // A, D, E: the same data, a different day boundary
        assertThat(utc.daily().get(0).date()).isEqualTo(java.time.LocalDate.parse("2026-03-10"));

        var multi = repository.report(Instant.parse("2026-03-08T18:30:00Z"), Instant.parse("2026-03-11T18:30:00Z"), "Asia/Kolkata").block();
        assertThat(multi.daily()).extracting(SupportCaseRepository.DayPoint::date).containsExactly(
            java.time.LocalDate.parse("2026-03-09"), java.time.LocalDate.parse("2026-03-10"), java.time.LocalDate.parse("2026-03-11"));
        assertThat(multi.daily().stream().mapToLong(SupportCaseRepository.DayPoint::created).sum()).isEqualTo(multi.createdInPeriod());
        var empty = repository.report(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-01-02T00:00:00Z"), "UTC").block();
        assertThat(empty.createdInPeriod()).isZero();
        assertThat(empty.daily()).hasSize(1).allSatisfy(d -> assertThat(d.created()).isZero());
    }

    @Test
    void a_stale_view_is_rejected_and_concurrent_writers_cannot_both_win() {
        var c = open(ALICE, "k-lock-1", CaseCategory.GENERAL, null, "General question").supportCase();
        var id = c.getId().toString();
        var seen = staff.get(id).block().supportCase().getVersion();
        staff.addNote(id, STAFF, "first", seen).block();
        assertThatThrownBy(() -> staff.addNote(id, STAFF, "second, based on the stale view", seen).block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
        var loaded = repository.findById(c.getId()).block();
        var stale = repository.findById(c.getId()).block();
        loaded.touch(Instant.now());
        repository.save(loaded).block();
        stale.touch(Instant.now());
        assertThatThrownBy(() -> repository.save(stale).block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.OPTIMISTIC_LOCKING_CONFLICT);
    }

    @Test
    void the_reopen_window_is_enforced_for_customers() {
        var c = open(ALICE, "k-window-1", CaseCategory.GENERAL, null, "Old question").supportCase();
        staff.resolve(c.getId().toString(), STAFF, ResolutionOutcome.ANSWERED, "Answered", null, null).block();
        db.sql("UPDATE support_cases SET resolved_at = now() - interval '9 days' WHERE id = :id").bind("id", c.getId()).fetch().rowsUpdated().block();
        assertThatThrownBy(() -> customer.reopen(c.getId().toString(), ALICE, "still broken").block()).isInstanceOf(SupportDomainException.class)
            .extracting("errorCode").isEqualTo(SupportErrorCode.REOPEN_NOT_ALLOWED);
        assertThat(staff.changeStatus(c.getId().toString(), STAFF, CaseStatus.INVESTIGATING, "Reopened by support", null).block().supportCase().getStatus())
            .isEqualTo(CaseStatus.INVESTIGATING);
    }

    // ------------------------------------------------------------------------------------------------ constraints, search, analytics

    @Test
    void the_database_itself_refuses_a_resolved_case_without_an_outcome() {
        var c = open(ALICE, "k-ck-1", CaseCategory.GENERAL, null, "Constraint").supportCase();
        assertThatThrownBy(() -> db.sql("UPDATE support_cases SET status = 'RESOLVED' WHERE id = :id").bind("id", c.getId()).fetch().rowsUpdated().block())
            .hasMessageContaining("ck_support_resolved_has_outcome");
        assertThatThrownBy(() -> db.sql("INSERT INTO support_case_messages (id, case_id, author_type, internal, body, created_at) VALUES (:i, :c, 'CUSTOMER', true, 'x', now())")
            .bind("i", UUID.randomUUID()).bind("c", c.getId()).fetch().rowsUpdated().block()).hasMessageContaining("ck_support_internal_is_staff");
    }

    @Test
    void staff_can_search_and_the_analytics_come_from_the_stored_cases() {
        open(ALICE, "k-search-1", CaseCategory.PAYMENT_ISSUE, null, "SEARCHME payment failed");
        var page = staff.search(new SupportCaseRepository.CaseFilter(null, "PAYMENT_ISSUE", null, null, null, null, null, "searchme"), 0, 10).block();
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().get(0).getSubject()).contains("SEARCHME");
        var all = staff.search(new SupportCaseRepository.CaseFilter(null, null, null, null, true, null, null, null), 0, 100).block();
        assertThat(all.items()).allMatch(c -> c.getAssignedTo() == null && c.getStatus().isUnresolved());
        var before = staff.analytics().block();
        var web = new CreateSupportCaseUseCase.Command(ALICE, "k-an-web", CaseCategory.GENERAL, null, "Analytics web case", "A question", null, null, null, null, null, false,
            null, List.of(), List.of(), List.of(), null, null, "WEB");
        create.execute(web).block();
        var ai = open(ALICE, "k-an-ai", CaseCategory.GENERAL, null, "Analytics assistant case").supportCase();
        staff.resolve(ai.getId().toString(), STAFF, ResolutionOutcome.NO_ACTION_NEEDED, "Nothing to do", null, null).block();
        customer.reopen(ai.getId().toString(), ALICE, "Actually there is").block();
        var a = staff.analytics().block();
        assertThat(a.totalCases() - before.totalCases()).isEqualTo(2);                     // the web case and the assistant case
        assertThat(a.createdByWeb() - before.createdByWeb()).isEqualTo(1);
        assertThat(a.createdByAssistant() - before.createdByAssistant()).isEqualTo(1);
        assertThat(a.reopenedCases() - before.reopenedCases()).isEqualTo(1);
        assertThat(a.byStatus().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(a.totalCases());
        assertThat(a.createdByAssistant() + a.createdByWeb()).isEqualTo(a.totalCases());
        assertThat(a.resolvedByHuman()).isEqualTo(a.resolvedByOutcome().values().stream().mapToLong(Long::longValue).sum());    }
}
