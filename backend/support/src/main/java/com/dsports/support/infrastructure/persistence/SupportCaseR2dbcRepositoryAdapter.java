package com.dsports.support.infrastructure.persistence;

import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.domain.exception.SupportDomainException;
import com.dsports.support.domain.exception.SupportErrorCode;
import com.dsports.support.domain.model.CaseCategory;
import com.dsports.support.domain.model.CaseEvent;
import com.dsports.support.domain.model.CaseMessage;
import com.dsports.support.domain.model.CasePriority;
import com.dsports.support.domain.model.CaseStatus;
import com.dsports.support.domain.model.EscalationState;
import com.dsports.support.domain.model.EvidenceItem;
import com.dsports.support.domain.model.ResolutionOutcome;
import com.dsports.support.domain.model.SupportCase;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SupportCaseR2dbcRepositoryAdapter implements SupportCaseRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<EvidenceItem>> EVIDENCE = new TypeReference<>() {};
    private static final String ORDER_BY_URGENCY =
        " ORDER BY CASE priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, created_at";

    private final SpringR2dbcSupportCaseRepository springRepository;
    private final DatabaseClient db;

    public SupportCaseR2dbcRepositoryAdapter(SpringR2dbcSupportCaseRepository springRepository, DatabaseClient db) {
        this.springRepository = springRepository;
        this.db = db;
    }

    // ------------------------------------------------------------------------------------------------ cases

    @Override
    public Mono<SupportCase> findById(UUID id) {
        return springRepository.findById(id).map(SupportCaseR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<SupportCase> findByCaseNumber(String caseNumber) {
        return springRepository.findByCaseNumber(caseNumber).map(SupportCaseR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Flux<SupportCase> findByUser(UUID userId) {
        return springRepository.findByUserIdOrderByUpdatedAtDesc(userId).map(SupportCaseR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<SupportCase> findByUserAndKey(UUID userId, String idempotencyKey) {
        return springRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(SupportCaseR2dbcRepositoryAdapter::toDomain);
    }

    @Override
    public Mono<SupportCase> findUnresolvedAbout(UUID userId, UUID orderId, CaseCategory category) {
        return db.sql("SELECT id FROM support_cases WHERE user_id = :u AND order_id = :o AND category = :c AND status <> 'RESOLVED' "
                + "ORDER BY created_at LIMIT 1")
            .bind("u", userId).bind("o", orderId).bind("c", category.name())
            .map((row, meta) -> row.get("id", UUID.class)).one()
            .flatMap(this::findById);
    }

    @Override
    public Mono<Integer> countCasesAboutOrder(UUID userId, UUID orderId) {
        return db.sql("SELECT COUNT(*) AS n FROM support_cases WHERE user_id = :u AND order_id = :o")
            .bind("u", userId).bind("o", orderId)
            .map((row, meta) -> row.get("n", Long.class).intValue()).one().defaultIfEmpty(0);
    }

    @Override
    public Mono<Long> nextSequence() {
        return db.sql("SELECT nextval('support_case_seq') AS n").map((row, meta) -> row.get("n", Long.class)).one();
    }

    @Override
    public Mono<Void> lockCreation(UUID userId, String idempotencyKey) {
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
            .bind("key", "support:" + userId + ":" + idempotencyKey).fetch().all().then();
    }

    @Override
    public Mono<Void> save(SupportCase c) {
        return springRepository.save(toEntity(c))
            .doOnNext(saved -> c.setVersion(saved.version))
            .onErrorMap(OptimisticLockingFailureException.class, e ->
                new SupportDomainException(SupportErrorCode.OPTIMISTIC_LOCKING_CONFLICT,
                    "Case " + c.getCaseNumber() + " was changed by someone else. Reload and retry."))
            .onErrorMap(DataIntegrityViolationException.class, e ->
                new SupportDomainException(SupportErrorCode.IDEMPOTENCY_KEY_REUSED, "A case with this Idempotency-Key already exists"))
            .then();
    }

    // ------------------------------------------------------------------------------------------------ messages and events

    @Override
    public Mono<Void> addMessage(CaseMessage m) {
        var spec = db.sql("INSERT INTO support_case_messages (id, case_id, author_type, author_id, internal, body, created_at) "
                + "VALUES (:id, :caseId, :type, :author, :internal, :body, :at)")
            .bind("id", m.id()).bind("caseId", m.caseId()).bind("type", m.authorType()).bind("internal", m.internal())
            .bind("body", m.body()).bind("at", m.createdAt());
        spec = m.authorId() == null ? spec.bindNull("author", UUID.class) : spec.bind("author", m.authorId());
        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<CaseMessage> messages(UUID caseId, boolean includeInternal) {
        return db.sql("SELECT id, case_id, author_type, author_id, internal, body, created_at FROM support_case_messages "
                + "WHERE case_id = :c" + (includeInternal ? "" : " AND NOT internal") + " ORDER BY created_at, id")
            .bind("c", caseId)
            .map((row, meta) -> new CaseMessage(row.get("id", UUID.class), row.get("case_id", UUID.class), row.get("author_type", String.class),
                row.get("author_id", UUID.class), Boolean.TRUE.equals(row.get("internal", Boolean.class)), row.get("body", String.class),
                row.get("created_at", Instant.class)))
            .all();
    }

    @Override
    public Mono<Void> addEvent(CaseEvent e) {
        var spec = db.sql("INSERT INTO support_case_events (id, case_id, event_type, actor_type, actor_id, from_status, to_status, detail, "
                + "customer_visible, created_at) VALUES (:id, :caseId, :type, :actorType, :actor, :from, :to, :detail, :visible, :at)")
            .bind("id", e.id()).bind("caseId", e.caseId()).bind("type", e.eventType()).bind("actorType", e.actorType())
            .bind("visible", e.customerVisible()).bind("at", e.createdAt());
        spec = e.actorId() == null ? spec.bindNull("actor", UUID.class) : spec.bind("actor", e.actorId());
        spec = e.fromStatus() == null ? spec.bindNull("from", String.class) : spec.bind("from", e.fromStatus());
        spec = e.toStatus() == null ? spec.bindNull("to", String.class) : spec.bind("to", e.toStatus());
        spec = e.detail() == null ? spec.bindNull("detail", String.class) : spec.bind("detail", e.detail());
        return spec.fetch().rowsUpdated().then();
    }

    @Override
    public Flux<CaseEvent> events(UUID caseId, boolean customerVisibleOnly) {
        return db.sql("SELECT id, case_id, event_type, actor_type, actor_id, from_status, to_status, detail, customer_visible, created_at "
                + "FROM support_case_events WHERE case_id = :c" + (customerVisibleOnly ? " AND customer_visible" : "") + " ORDER BY created_at, id")
            .bind("c", caseId)
            .map((row, meta) -> new CaseEvent(row.get("id", UUID.class), row.get("case_id", UUID.class), row.get("event_type", String.class),
                row.get("actor_type", String.class), row.get("actor_id", UUID.class), row.get("from_status", String.class),
                row.get("to_status", String.class), row.get("detail", String.class),
                Boolean.TRUE.equals(row.get("customer_visible", Boolean.class)), row.get("created_at", Instant.class)))
            .all();
    }

    // ------------------------------------------------------------------------------------------------ staff search

    private record Where(String sql, Map<String, Object> binds) {}

    private static Where where(CaseFilter f) {
        var sql = new StringBuilder(" WHERE 1 = 1");
        var binds = new LinkedHashMap<String, Object>();
        if (f.status() != null) { sql.append(" AND status = :status"); binds.put("status", f.status()); }
        if (f.category() != null) { sql.append(" AND category = :category"); binds.put("category", f.category()); }
        if (f.priority() != null) { sql.append(" AND priority = :priority"); binds.put("priority", f.priority()); }
        if (f.escalation() != null) { sql.append(" AND escalation_state = :escalation"); binds.put("escalation", f.escalation()); }
        if (Boolean.TRUE.equals(f.unassigned())) { sql.append(" AND assigned_to IS NULL AND status <> 'RESOLVED'"); }
        if (f.assignedTo() != null) { sql.append(" AND assigned_to = :assignedTo"); binds.put("assignedTo", f.assignedTo()); }
        if (f.queue() != null) { sql.append(" AND assigned_queue = :queue"); binds.put("queue", f.queue()); }
        if (f.createdFrom() != null) { sql.append(" AND created_at >= :createdFrom"); binds.put("createdFrom", f.createdFrom()); }
        if (f.createdTo() != null) { sql.append(" AND created_at < :createdTo"); binds.put("createdTo", f.createdTo()); }
        if (f.customerId() != null) { sql.append(" AND user_id = :customerId"); binds.put("customerId", f.customerId()); }
        if (f.text() != null && !f.text().isBlank()) {
            sql.append(" AND (lower(subject) LIKE :q OR lower(case_number) LIKE :q OR lower(order_number) LIKE :q)");
            binds.put("q", "%" + f.text().trim().toLowerCase().replace("%", "").replace("_", "") + "%");
        }
        return new Where(sql.toString(), binds);
    }

    private static DatabaseClient.GenericExecuteSpec bindAll(DatabaseClient.GenericExecuteSpec spec, Map<String, Object> binds) {
        for (var e : binds.entrySet()) {
            spec = spec.bind(e.getKey(), e.getValue());
        }
        return spec;
    }

    @Override
    public Flux<SupportCase> search(CaseFilter filter, int page, int size) {
        var w = where(filter);
        var spec = bindAll(db.sql("SELECT id FROM support_cases" + w.sql() + ORDER_BY_URGENCY + " LIMIT " + size + " OFFSET " + (long) page * size), w.binds());
        return spec.map((row, meta) -> row.get("id", UUID.class)).all().collectList()
            .flatMapMany(ids -> springRepository.findAllById(ids).collectMap(e -> e.id).flatMapMany(byId ->
                Flux.fromIterable(ids).filter(byId::containsKey).map(id -> toDomain(byId.get(id)))));
    }

    @Override
    public Mono<Long> count(CaseFilter filter) {
        var w = where(filter);
        return bindAll(db.sql("SELECT COUNT(*) AS n FROM support_cases" + w.sql()), w.binds()).map((row, meta) -> row.get("n", Long.class)).one();
    }

    // ------------------------------------------------------------------------------------------------ analytics

    private Mono<Map<String, Long>> grouped(String column, String where) {
        return db.sql("SELECT " + column + " AS k, COUNT(*) AS n FROM support_cases " + where + " GROUP BY 1")
            .map((row, meta) -> Map.entry(String.valueOf(row.get("k", String.class)), row.get("n", Long.class))).all()
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Long> scalar(String sql) {
        return db.sql(sql).map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    private Mono<Double> hours(String aggregate) {
        return db.sql("SELECT " + aggregate + "(EXTRACT(EPOCH FROM (now() - created_at)) / 3600.0)::float8 AS v FROM support_cases WHERE status <> 'RESOLVED'")
            .map((row, meta) -> java.util.Optional.ofNullable(row.get("v", Double.class)).orElse(-1.0)).one();   // a mapper must not return null
    }

    @Override
    public Mono<Analytics> analytics() {
        List<Mono<?>> parts = List.of(
            scalar("SELECT COUNT(*) AS n FROM support_cases"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE status <> 'RESOLVED'"),
            grouped("status", ""), grouped("category", ""), grouped("priority", "WHERE status <> 'RESOLVED'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE escalation_state = 'REQUESTED' AND status <> 'RESOLVED'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE escalation_state = 'ACCEPTED'"),
            grouped("resolution_outcome", "WHERE status = 'RESOLVED'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE status = 'RESOLVED' AND resolved_by IS NOT NULL"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE reopen_count > 0"),
            scalar("SELECT COUNT(*) AS n FROM support_cases c WHERE order_id IS NOT NULL AND (SELECT COUNT(*) FROM support_cases d "
                + "WHERE d.user_id = c.user_id AND d.order_id = c.order_id) > 1"),
            hours("AVG").defaultIfEmpty(-1.0), hours("MAX").defaultIfEmpty(-1.0),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE source = 'AI_ASSISTANT'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE source = 'WEB'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE assigned_to IS NULL AND status <> 'RESOLVED'"));
        return Mono.zip(parts, o -> {
            @SuppressWarnings("unchecked") var byStatus = (Map<String, Long>) o[2];
            @SuppressWarnings("unchecked") var byCategory = (Map<String, Long>) o[3];
            @SuppressWarnings("unchecked") var byPriority = (Map<String, Long>) o[4];
            @SuppressWarnings("unchecked") var byOutcome = (Map<String, Long>) o[7];
            double avg = (Double) o[11];
            double oldest = (Double) o[12];
            return new Analytics((Long) o[0], (Long) o[1], byStatus, byCategory, byPriority, (Long) o[5], (Long) o[6], byOutcome, (Long) o[8],
                (Long) o[9], (Long) o[10], avg < 0 ? null : avg, oldest < 0 ? null : oldest, (Long) o[13], (Long) o[14], (Long) o[15]);
        });
    }

    @Override
    public Mono<Report> report(Instant from, Instant to, String zone) {
        var period = "created_at >= :f AND created_at < :t";
        var fromDate = from.atZone(java.time.ZoneId.of(zone)).toLocalDate();
        var toDate = to.minusNanos(1).atZone(java.time.ZoneId.of(zone)).toLocalDate();
        Mono<Long> created = count("SELECT COUNT(*) AS n FROM support_cases WHERE " + period, from, to);
        Mono<Long> resolved = count("SELECT COUNT(*) AS n FROM support_cases WHERE resolved_at >= :f AND resolved_at < :t", from, to);
        Mono<Long> reopened = count("SELECT COUNT(DISTINCT case_id) AS n FROM support_case_events WHERE event_type = 'REOPENED' AND created_at >= :f AND created_at < :t", from, to);
        Mono<Long> escalated = count("SELECT COUNT(DISTINCT case_id) AS n FROM support_case_events WHERE event_type = 'ESCALATION_REQUESTED' AND created_at >= :f AND created_at < :t", from, to);
        Mono<Long> stillUnresolved = count("SELECT COUNT(*) AS n FROM support_cases WHERE " + period + " AND status <> 'RESOLVED'", from, to);
        Mono<Map<String, Long>> byCategory = groupedIn("category", period, from, to);
        Mono<Map<String, Long>> byPriority = groupedIn("priority", period, from, to);
        Mono<List<DayPoint>> daily = db.sql("SELECT g.d::date AS day, "
                + "(SELECT COUNT(*) FROM support_cases c WHERE (c.created_at AT TIME ZONE CAST(:z AS text))::date = g.d::date) AS created, "
                + "(SELECT COUNT(*) FROM support_cases c WHERE c.resolved_at IS NOT NULL AND (c.resolved_at AT TIME ZONE CAST(:z AS text))::date = g.d::date) AS resolved "
                + "FROM generate_series(CAST(:a AS date), CAST(:b AS date), interval '1 day') AS g(d) ORDER BY 1")
            .bind("z", zone).bind("a", fromDate).bind("b", toDate)
            .map((row, meta) -> new DayPoint(row.get("day", java.time.LocalDate.class), row.get("created", Long.class), row.get("resolved", Long.class))).all().collectList();
        List<Mono<?>> parts = List.of(created, resolved, reopened, escalated,
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE status = 'OPEN'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE status IN ('INVESTIGATING', 'WAITING_FOR_CUSTOMER', 'WAITING_FOR_EXTERNAL_PARTY')"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE status <> 'RESOLVED'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE assigned_to IS NULL AND status <> 'RESOLVED'"),
            scalar("SELECT COUNT(*) AS n FROM support_cases WHERE escalation_state = 'REQUESTED' AND status <> 'RESOLVED'"),
            stillUnresolved, byCategory, byPriority, daily, hours("MAX").defaultIfEmpty(-1.0));
        return Mono.zip(parts, o -> {
            @SuppressWarnings("unchecked") var cat = (Map<String, Long>) o[10];
            @SuppressWarnings("unchecked") var pri = (Map<String, Long>) o[11];
            @SuppressWarnings("unchecked") var days = (List<DayPoint>) o[12];
            double oldest = (Double) o[13];
            return new Report(from, to, zone, (Long) o[0], (Long) o[1], (Long) o[2], (Long) o[3], (Long) o[4], (Long) o[5], (Long) o[6], (Long) o[7], (Long) o[8], (Long) o[9],
                cat, pri, days, oldest < 0 ? null : oldest);
        });
    }

    private Mono<Long> count(String sql, Instant from, Instant to) {
        return db.sql(sql).bind("f", from).bind("t", to).map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    private Mono<Map<String, Long>> groupedIn(String column, String where, Instant from, Instant to) {
        return db.sql("SELECT " + column + " AS k, COUNT(*) AS n FROM support_cases WHERE " + where + " GROUP BY 1").bind("f", from).bind("t", to)
            .map((row, meta) -> Map.entry(String.valueOf(row.get("k", String.class)), row.get("n", Long.class))).all()
            .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ------------------------------------------------------------------------------------------------ mapping

    private static SupportCaseEntity toEntity(SupportCase c) {
        var e = new SupportCaseEntity();
        e.id = c.getId();
        e.caseNumber = c.getCaseNumber();
        e.userId = c.getUserId();
        e.category = c.getCategory().name();
        e.subcategory = c.getSubcategory();
        e.priority = c.getPriority().name();
        e.priorityReasons = json(c.getPriorityReasons());
        e.status = c.getStatus().name();
        e.subject = c.getSubject();
        e.description = c.getDescription();
        e.orderId = c.getOrderId();
        e.orderNumber = c.getOrderNumber();
        e.productId = c.getProductId();
        e.productName = c.getProductName();
        e.paymentId = c.getPaymentId();
        e.returnId = c.getReturnId();
        e.refundReference = c.getRefundReference();
        e.orderStatusAtCreation = c.getOrderStatusAtCreation();
        e.shipmentStatusAtCreation = c.getShipmentStatusAtCreation();
        e.orderTotal = c.getOrderTotal();
        e.currency = c.getCurrency();
        e.investigationSummary = c.getInvestigationSummary();
        e.evidence = json(c.getEvidence());
        e.policyCitations = json(c.getPolicyCitations());
        e.missingInformation = json(c.getMissingInformation());
        e.resolutionRecommendation = c.getResolutionRecommendation();
        e.resolutionOutcome = c.getResolutionOutcome() == null ? null : c.getResolutionOutcome().name();
        e.resolutionNote = c.getResolutionNote();
        e.resolvedBy = c.getResolvedBy();
        e.assignedQueue = c.getAssignedQueue();
        e.assignedTo = c.getAssignedTo();
        e.escalationState = c.getEscalationState().name();
        e.escalationReason = c.getEscalationReason();
        e.safetyRelated = c.isSafetyRelated();
        e.source = c.getSource();
        e.idempotencyKey = c.getIdempotencyKey();
        e.reopenCount = c.getReopenCount();
        e.version = c.getVersion();
        e.createdAt = c.getCreatedAt();
        e.updatedAt = c.getUpdatedAt();
        e.resolvedAt = c.getResolvedAt();
        return e;
    }

    private static SupportCase toDomain(SupportCaseEntity e) {
        var data = new SupportCase.OpenData(e.userId, CaseCategory.valueOf(e.category), e.subcategory, e.subject, e.description, e.orderId,
            e.orderNumber, e.productId, e.productName, e.paymentId, e.returnId, e.refundReference, e.orderStatusAtCreation,
            e.shipmentStatusAtCreation, e.orderTotal, e.currency, e.investigationSummary, read(e.evidence, EVIDENCE), read(e.policyCitations, STRINGS),
            read(e.missingInformation, STRINGS), e.resolutionRecommendation, null, e.safetyRelated, e.source, e.idempotencyKey);
        return SupportCase.reconstitute(e.id, e.caseNumber, data, CasePriority.valueOf(e.priority), read(e.priorityReasons, STRINGS),
            CaseStatus.valueOf(e.status), e.resolutionOutcome == null ? null : ResolutionOutcome.valueOf(e.resolutionOutcome), e.resolutionNote,
            e.resolvedBy, e.assignedQueue, e.assignedTo, EscalationState.valueOf(e.escalationState), e.escalationReason, e.reopenCount,
            e.version, e.createdAt, e.updatedAt, e.resolvedAt);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialise case data", ex);
        }
    }

    private static <T> List<T> read(String value, TypeReference<List<T>> type) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Stored case data is not readable", ex);
        }
    }
}
