package com.dsports.returns.infrastructure.persistence;

import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The approved-returns read port against a real PostgreSQL: only APPROVED requests count, summed per order and product. */
@Tag("integration")
class ApprovedReturnsReaderIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static ApprovedReturnsR2dbcReader reader;
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID OTHER_ORDER = UUID.randomUUID();
    private static final UUID BAT = UUID.randomUUID();
    private static final UUID BALL = UUID.randomUUID();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName()));
        reader = new ApprovedReturnsR2dbcReader(db);
        request(ORDER, BAT, 1, "APPROVED", "a1");
        request(ORDER, BAT, 2, "APPROVED", "a2");           // two approved requests for the same line: summed
        request(ORDER, BAT, 5, "REJECTED", "a3");           // not a return
        request(ORDER, BALL, 3, "REQUESTED", "a4");         // not decided yet: not a return
        request(OTHER_ORDER, BAT, 4, "APPROVED", "a5");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static void request(UUID order, UUID product, int qty, String status, String key) {
        db.sql("INSERT INTO return_requests (id, order_id, user_id, product_id, quantity, reason, idempotency_key, status, created_at, updated_at) "
                + "VALUES (:i, :o, :u, :p, :q, 'x', :k, :s, now(), now())")
            .bind("i", UUID.randomUUID()).bind("o", order).bind("u", UUID.randomUUID()).bind("p", product).bind("q", qty).bind("k", key).bind("s", status).fetch().rowsUpdated().block();
    }

    @Test
    void only_approved_requests_count_and_they_are_summed_per_order_and_product() {
        var result = reader.approvedFor(List.of(ORDER)).collectList().block();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).productId()).isEqualTo(BAT);
        assertThat(result.get(0).quantity()).isEqualTo(3);
    }

    @Test
    void several_orders_are_answered_in_one_query_and_an_empty_list_asks_nothing() {
        assertThat(reader.approvedFor(List.of(ORDER, OTHER_ORDER)).collectList().block()).hasSize(2);
        assertThat(reader.approvedFor(List.of()).collectList().block()).isEmpty();
        assertThat(reader.approvedFor(List.of(UUID.randomUUID())).collectList().block()).isEmpty();
    }
}
