package com.dsports.returns.infrastructure.persistence;

import com.dsports.returns.application.port.ReturnOrderPort;
import com.dsports.returns.application.usecase.CreateReturnUseCase;
import com.dsports.returns.domain.exception.ReturnsDomainException;
import com.dsports.returns.domain.exception.ReturnsErrorCode;
import com.dsports.returns.domain.model.ReturnRequest;
import com.dsports.returns.domain.model.ReturnStatus;
import com.dsports.returns.domain.policy.ReturnPolicy;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderFacts;
import com.dsports.returns.domain.policy.ReturnPolicy.OrderItemFacts;
import com.dsports.shared.application.TransactionRunner;
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
import reactor.test.StepVerifier;

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

/**
 * Return persistence, idempotency and the quantity guard against a real PostgreSQL: the unique key, CHECK
 * constraints, optimistic locking and the advisory lock that serialises concurrent returns of one order line.
 */
@Tag("integration")
class ReturnsIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Instant DELIVERED = Instant.parse("2026-01-10T10:00:00Z");

    private static DatabaseClient db;
    private static TransactionRunner transactions;
    private static ReturnR2dbcRepositoryAdapter repository;
    private static CreateReturnUseCase createReturn;
    private static OrderFacts order;
    private static UUID orderId;
    private static UUID productId;
    private static UUID userId;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        ConnectionFactory connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername()
            + ":" + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/"
            + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(connectionFactory);
        var operator = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
        transactions = new TransactionRunner() {
            @Override
            public <T> Mono<T> inTransaction(Supplier<Mono<T>> work) {
                return Mono.defer(work).as(operator::transactional);
            }
        };
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        repository = new ReturnR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcReturnRepository.class), db);

        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        productId = UUID.randomUUID();
        order = new OrderFacts(orderId, true, "DELIVERED", DELIVERED, "INR",
            Map.of(productId, new OrderItemFacts("Bat", 5, new BigDecimal("1500.00"))));
        ReturnOrderPort orders = (id, customer) -> id.equals(orderId) && customer.equals(userId)
            ? Mono.just(order) : Mono.empty();
        var clock = Clock.fixed(DELIVERED.plus(Duration.ofDays(1)), ZoneOffset.UTC);
        createReturn = new CreateReturnUseCase(orders, repository, new ReturnPolicy(Duration.ofDays(7)), transactions, clock);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static CreateReturnUseCase.Command command(UUID order, UUID product, int quantity, String key) {
        return new CreateReturnUseCase.Command(userId, order, product, quantity, "Damaged", key);
    }

    private static long count(String where) {
        return db.sql("SELECT count(*) AS c FROM return_requests WHERE " + where)
            .map((row, meta) -> row.get("c", Long.class)).one().block();
    }

    @Test
    void savesAndReloadsARequest() {
        var request = ReturnRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Ball", 2, "Wrong size", "k-roundtrip");

        StepVerifier.create(repository.save(request).then(repository.findById(request.getId())))
            .assertNext(loaded -> {
                assertThat(loaded.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
                assertThat(loaded.getQuantity()).isEqualTo(2);
                assertThat(loaded.getProductName()).isEqualTo("Ball");
                assertThat(loaded.getVersion()).isEqualTo(1);
            })
            .verifyComplete();
    }

    @Test
    void databaseEnforcesOneRequestPerCustomerAndKey() {
        var customer = UUID.randomUUID();
        var first = ReturnRequest.create(UUID.randomUUID(), customer, UUID.randomUUID(), "A", 1, "r", "same-key");
        var second = ReturnRequest.create(UUID.randomUUID(), customer, UUID.randomUUID(), "B", 1, "r", "same-key");
        var otherCustomer = ReturnRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "C", 1, "r", "same-key");

        StepVerifier.create(repository.save(first).then(repository.save(second)))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.IDEMPOTENCY_KEY_REUSED)
            .verify();
        StepVerifier.create(repository.save(otherCustomer)).verifyComplete();
    }

    @Test
    void databaseRejectsNonPositiveQuantity() {
        StepVerifier.create(db.sql("""
                INSERT INTO return_requests (id, order_id, user_id, product_id, quantity, reason, idempotency_key, status, created_at, updated_at)
                VALUES (:id, :o, :u, :p, 0, 'x', 'k-zero', 'REQUESTED', now(), now())
                """)
                .bind("id", UUID.randomUUID()).bind("o", UUID.randomUUID()).bind("u", UUID.randomUUID())
                .bind("p", UUID.randomUUID()).fetch().rowsUpdated())
            .expectErrorMatches(e -> e.getMessage().contains("ck_return_quantity_positive"))
            .verify();
    }

    @Test
    void staleUpdateIsRejectedByOptimisticLocking() {
        var request = ReturnRequest.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Ball", 1, "r", "k-stale");
        repository.save(request).block();
        var copyA = repository.findById(request.getId()).block();
        var copyB = repository.findById(request.getId()).block();
        copyA.approve("ok");
        copyB.reject("no");

        repository.save(copyA).block();

        StepVerifier.create(repository.save(copyB))
            .expectErrorMatches(e -> e instanceof ReturnsDomainException d
                && d.getErrorCode() == ReturnsErrorCode.OPTIMISTIC_LOCKING_CONFLICT)
            .verify();
        assertThat(repository.findById(request.getId()).block().getStatus()).isEqualTo(ReturnStatus.APPROVED);
    }

    @Test
    void heldQuantityCountsOpenAndApprovedButNotRejected() {
        var o = UUID.randomUUID();
        var p = UUID.randomUUID();
        var requested = ReturnRequest.create(o, UUID.randomUUID(), p, "X", 2, "r", "k-held-1");
        var approved = ReturnRequest.create(o, UUID.randomUUID(), p, "X", 3, "r", "k-held-2");
        var rejected = ReturnRequest.create(o, UUID.randomUUID(), p, "X", 4, "r", "k-held-3");
        approved.approve(null);
        rejected.reject("no");
        Flux.just(requested, approved, rejected).concatMap(repository::save).blockLast();

        StepVerifier.create(repository.sumHeldQuantity(o, p)).expectNext(5).verifyComplete();
        StepVerifier.create(repository.sumHeldQuantity(UUID.randomUUID(), p)).expectNext(0).verifyComplete();
    }

    @Test
    void concurrentReturnsNeverExceedTheOrderedQuantity() {
        // 5 units ordered, 12 customers-requests race for 1 unit each
        var results = Flux.range(0, 12)
            .flatMap(i -> createReturn.execute(command(orderId, productId, 1, "race-" + i))
                .map(r -> "created")
                .onErrorResume(ReturnsDomainException.class, e -> Mono.just(e.getErrorCode().name()))
                .subscribeOn(Schedulers.boundedElastic()), 12)
            .collectList().block(Duration.ofSeconds(60));

        assertThat(results).filteredOn("created"::equals).hasSize(5);
        assertThat(results).filteredOn(r -> !r.equals("created")).hasSize(7).containsOnly("NOT_ELIGIBLE");
        assertThat(count("order_id='" + orderId + "' AND product_id='" + productId + "'")).isEqualTo(5);
        assertThat(repository.sumHeldQuantity(orderId, productId).block()).isEqualTo(5);
    }

    @Test
    void concurrentRetriesOfOneKeyCreateOneRequest() {
        var order2 = UUID.randomUUID();
        var product2 = UUID.randomUUID();
        var facts = new OrderFacts(order2, true, "DELIVERED", DELIVERED, "INR",
            Map.of(product2, new OrderItemFacts("Pads", 2, new BigDecimal("999.00"))));
        var useCase = new CreateReturnUseCase((id, customer) -> Mono.just(facts), repository,
            new ReturnPolicy(Duration.ofDays(7)), transactions, Clock.fixed(DELIVERED.plusSeconds(60), ZoneOffset.UTC));

        List<UUID> ids = Flux.range(0, 8)
            .flatMap(i -> useCase.execute(new CreateReturnUseCase.Command(userId, order2, product2, 1, "r", "one-key"))
                .map(ReturnRequest::getId).subscribeOn(Schedulers.boundedElastic()), 8)
            .collectList().block(Duration.ofSeconds(60));

        assertThat(ids).hasSize(8).containsOnly(ids.get(0));
        assertThat(count("order_id='" + order2 + "'")).isEqualTo(1);
    }
}
