package com.dsports.inventory.infrastructure.persistence;

import com.dsports.inventory.application.port.EventPublisher;
import com.dsports.inventory.application.usecase.FulfillOrderReservationsUseCase;
import com.dsports.inventory.application.usecase.ReleaseOrderReservationsUseCase;
import com.dsports.inventory.application.usecase.ReserveStockForOrderUseCase;
import com.dsports.inventory.infrastructure.persistence.mapper.InventoryEntityMapper;
import com.dsports.inventory.infrastructure.persistence.repository.InventoryR2dbcRepositoryAdapter;
import com.dsports.inventory.infrastructure.persistence.repository.SpringR2dbcInventoryRepository;
import com.dsports.inventory.infrastructure.persistence.repository.SpringR2dbcStockReservationRepository;
import com.dsports.inventory.infrastructure.persistence.repository.StockReservationR2dbcRepositoryAdapter;
import com.dsports.shared.domain.kernel.DomainEvent;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overselling guard against a real PostgreSQL: concurrent customers, each in their own transaction (as in order
 * placement), compete for the same inventory row.
 */
@Tag("integration")
class StockReservationConcurrencyIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static ConnectionFactory connectionFactory;
    private static DatabaseClient db;
    private static TransactionalOperator tx;
    private static ReserveStockForOrderUseCase reserve;
    private static FulfillOrderReservationsUseCase fulfill;
    private static ReleaseOrderReservationsUseCase release;

    private UUID productId;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();
        connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
            + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/"
            + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(connectionFactory);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));

        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        var noEvents = new EventPublisher() {
            @Override
            public void publish(DomainEvent event) {
            }

            @Override
            public void publishAll(List<DomainEvent> events) {
            }
        };
        var inventory = new InventoryR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcInventoryRepository.class),
            new InventoryEntityMapper(), noEvents, tx);
        var reservations = new StockReservationR2dbcRepositoryAdapter(
            factory.getRepository(SpringR2dbcStockReservationRepository.class));
        reserve = new ReserveStockForOrderUseCase(inventory, reservations);
        fulfill = new FulfillOrderReservationsUseCase(inventory, reservations);
        release = new ReleaseOrderReservationsUseCase(inventory, reservations);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createProduct() {
        productId = UUID.randomUUID();
    }

    @Test
    void tenCustomersRacingForTheLastUnitProduceExactlyOneReservation() {
        insertStock(1);

        var successes = race(10, 1);

        assertThat(successes).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
        assertThat(reservationRows()).isEqualTo(1);
    }

    @Test
    void contendedStockIsNeverOversold() {
        insertStock(5);

        var successes = race(20, 1);

        assertThat(successes).isBetween(1L, 5L);
        assertThat(reserved()).isEqualTo(successes);
        assertThat(reservationRows()).isEqualTo(successes);
    }

    @Test
    void releaseAndFulfilmentKeepCountersConsistent() {
        insertStock(10);
        var paid = UUID.randomUUID();
        var cancelled = UUID.randomUUID();
        reserveInTransaction(paid, 3).block();
        reserveInTransaction(cancelled, 4).block();

        fulfill.execute(paid).as(tx::transactional).block();
        release.execute(cancelled).as(tx::transactional).block();

        assertThat(available()).isEqualTo(7);
        assertThat(reserved()).isZero();
        assertThat(db.sql("SELECT string_agg(status, ',' ORDER BY status) FROM inventory_reservations WHERE product_id = :p")
            .bind("p", productId).map(row -> row.get(0, String.class)).one().block())
            .isEqualTo("FULFILLED,RELEASED");
    }

    @Test
    void databaseRejectsReservingMoreThanOnHandEvenIfApplicationChecksAreBypassed() {
        insertStock(2);

        var violation = db.sql("UPDATE inventory SET reserved_quantity = 3 WHERE product_id = :p")
            .bind("p", productId).fetch().rowsUpdated()
            .then(Mono.just("updated"))
            .onErrorResume(e -> Mono.just(e.getMessage()))
            .block();

        assertThat(violation).contains("ck_inventory_reserved_within_available");
    }

    private long race(int customers, int quantity) {
        return Flux.range(0, customers)
            .parallel(customers)
            .runOn(Schedulers.boundedElastic())
            .flatMap(i -> reserveInTransaction(UUID.randomUUID(), quantity)
                .thenReturn(1L)
                .onErrorResume(e -> Mono.just(0L)))
            .sequential()
            .reduce(0L, Long::sum)
            .block(Duration.ofSeconds(60));
    }

    private Mono<Void> reserveInTransaction(UUID orderId, int quantity) {
        return reserve.execute(orderId, List.of(new ReserveStockForOrderUseCase.Line(productId, quantity)))
            .as(tx::transactional);
    }

    private void insertStock(int available) {
        db.sql("""
                INSERT INTO inventory (id, product_id, warehouse_id, available_quantity, reserved_quantity, reorder_level,
                                       status, version, created_at, updated_at)
                VALUES (:id, :p, :w, :a, 0, 0, 'IN_STOCK', 1, :now, :now)
                """)
            .bind("id", UUID.randomUUID()).bind("p", productId).bind("w", UUID.randomUUID())
            .bind("a", available).bind("now", Instant.now())
            .fetch().rowsUpdated().block();
    }

    private long reserved() {
        return db.sql("SELECT reserved_quantity FROM inventory WHERE product_id = :p").bind("p", productId)
            .map(row -> row.get(0, Integer.class)).one().block();
    }

    private long available() {
        return db.sql("SELECT available_quantity FROM inventory WHERE product_id = :p").bind("p", productId)
            .map(row -> row.get(0, Integer.class)).one().block();
    }

    private long reservationRows() {
        return db.sql("SELECT count(*) FROM inventory_reservations WHERE product_id = :p AND status = 'RESERVED'")
            .bind("p", productId).map(row -> row.get(0, Long.class)).one().block();
    }
}
