package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.application.order.usecase.GetCoPurchasesUseCase;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Co-purchase evidence against a real PostgreSQL: distinct orders, the minimum-evidence threshold and cancelled orders. */
@Tag("integration")
class CoPurchaseIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static GetCoPurchasesUseCase useCase;
    private static final UUID BAT = UUID.randomUUID();
    private static final UUID GRIP = UUID.randomUUID();
    private static final UUID BALL = UUID.randomUUID();
    private static final UUID RARE = UUID.randomUUID();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName()));
        useCase = new GetCoPurchasesUseCase(new CoPurchaseR2dbcAdapter(db), 2);
        order("CANCELLED", BAT, GRIP);                      // does not count
        order("DELIVERED", BAT, GRIP);
        order("DELIVERED", BAT, GRIP, BALL);
        order("CONFIRMED", BAT, BALL);
        order("DELIVERED", BAT, RARE);                      // only one order: below the threshold
        order("DELIVERED", BAT, BAT, GRIP);                 // the same product twice in one order is still one order
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static void order(String status, UUID... products) {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO orders (id, order_number, user_id, checkout_id, status) VALUES (:id, :n, :u, :c, :s)")
            .bind("id", id).bind("n", "ORD-" + id.toString().substring(0, 8)).bind("u", UUID.randomUUID()).bind("c", UUID.randomUUID()).bind("s", status).fetch().rowsUpdated().block();
        for (var p : products) {
            db.sql("INSERT INTO order_items (id, order_id, product_id, product_name, sku, quantity, unit_price, line_total) VALUES (:i, :o, :p, 'x', 'SKU', 1, 10, 10)")
                .bind("i", UUID.randomUUID()).bind("o", id).bind("p", p).fetch().rowsUpdated().block();
        }
    }

    @Test
    void only_pairs_with_enough_distinct_non_cancelled_orders_are_reported_strongest_first() {
        var result = useCase.execute(BAT, 0, 10).collectList().block();
        assertThat(result).extracting(c -> c.productId()).containsExactly(GRIP, BALL);
        assertThat(result.get(0).orders()).isEqualTo(3);        // delivered x2 + the order with the bat twice; the cancelled one is excluded
        assertThat(result.get(1).orders()).isEqualTo(2);
    }

    @Test
    void the_server_side_minimum_cannot_be_lowered_by_the_caller_but_can_be_raised() {
        assertThat(useCase.execute(BAT, 1, 10).collectList().block()).extracting(c -> c.productId()).doesNotContain(RARE);
        assertThat(useCase.execute(BAT, 3, 10).collectList().block()).extracting(c -> c.productId()).containsExactly(GRIP);
    }

    @Test
    void a_product_nobody_bought_has_no_evidence() {
        assertThat(useCase.execute(UUID.randomUUID(), 0, 10).collectList().block()).isEmpty();
    }
}
