package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.application.order.usecase.GetSalesFeedUseCase;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The planning sales feed against a real PostgreSQL: lines, status, approved returns, discount allocation, place without personal data, and the incremental cursor. */
@Tag("integration")
class SalesFeedIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static GetSalesFeedUseCase feed;
    private static final UUID BAT = UUID.randomUUID();
    private static final UUID BALL = UUID.randomUUID();
    private static UUID deliveredOrder;
    /** What the returns module would report as approved (stubbed: the order module never reads that table). */
    private static final java.util.List<com.dsports.order.application.order.port.ApprovedReturnsPort.ApprovedReturn> APPROVED = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();
        db = DatabaseClient.create(ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName()));
        feed = new GetSalesFeedUseCase(new SalesFeedR2dbcAdapter(db), orderIds -> reactor.core.publisher.Flux.fromIterable(APPROVED.stream().filter(a -> orderIds.contains(a.orderId())).toList()));
        deliveredOrder = order("DELIVERED", "2026-09-07T10:00:00Z", "{\"city\":\"Bengaluru\",\"state\":\"KA\",\"country\":\"IN\",\"fullName\":\"Asha Rao\",\"phone\":\"+91-98765-43210\",\"postalCode\":\"560001\"}", "300.00", "30.00", line(BAT, 2, "100.00"), line(BALL, 2, "50.00"));
        order("CANCELLED", "2026-09-08T10:00:00Z", "{\"city\":\"Pune\",\"state\":\"MH\",\"country\":\"IN\"}", "100.00", "0.00", line(BAT, 1, "100.00"));
        order("CREATED", null, null, "100.00", "0.00", line(BAT, 1, "100.00"));                    // never placed: not a sale
        order("CONFIRMED", "2026-09-20T10:00:00Z", "not json", "100.00", "0.00", line(BALL, 1, "100.00"));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private record Line(UUID product, int qty, String unit) {}

    private static Line line(UUID p, int qty, String unit) {
        return new Line(p, qty, unit);
    }

    private static UUID order(String status, String placedAt, String address, String subtotal, String discount, Line... lines) {
        var id = UUID.randomUUID();
        db.sql("INSERT INTO orders (id, order_number, user_id, checkout_id, status, subtotal, discount_amount) VALUES (:id, :n, :u, :c, :s, CAST(:sub AS numeric), CAST(:d AS numeric))")
            .bind("id", id).bind("n", "ORD-" + id.toString().substring(0, 8)).bind("u", UUID.randomUUID()).bind("c", UUID.randomUUID()).bind("s", status)
            .bind("sub", subtotal).bind("d", discount).fetch().rowsUpdated().block();
        if (placedAt != null) db.sql("UPDATE orders SET placed_at = CAST(:p AS timestamptz) WHERE id = :id").bind("p", placedAt).bind("id", id).fetch().rowsUpdated().block();
        if (address != null) db.sql("UPDATE orders SET shipping_address_snapshot = :a WHERE id = :id").bind("a", address).bind("id", id).fetch().rowsUpdated().block();
        for (var l : lines) {
            db.sql("INSERT INTO order_items (id, order_id, product_id, product_name, sku, quantity, unit_price, line_total) VALUES (:i, :o, :p, 'x', 'SKU', :q, CAST(:u AS numeric), CAST(:u AS numeric) * :q)")
                .bind("i", UUID.randomUUID()).bind("o", id).bind("p", l.product()).bind("q", l.qty()).bind("u", l.unit()).fetch().rowsUpdated().block();
        }
        return id;
    }

    @Test
    void only_placed_orders_appear_with_status_discount_allocation_and_no_personal_data() {
        var page = feed.execute(null, null, null, null, 100).block();
        assertThat(page.items()).isNotEmpty().noneMatch(l -> l.orderStatus().equals("CREATED"));      // the order that was never placed is absent
        var bat = page.items().stream().filter(l -> l.orderId().equals(deliveredOrder) && l.productId().equals(BAT)).findFirst().orElseThrow();
        assertThat(bat.orderStatus()).isEqualTo("DELIVERED");
        assertThat(bat.shipCity()).isEqualTo("Bengaluru");
        assertThat(bat.shipState()).isEqualTo("KA");
        assertThat(bat.allocatedDiscount()).isEqualByComparingTo("20.00");               // the order discount 30.00 split by line value: the bat line is 200.00 of the 300.00 subtotal
        assertThat(page.items().toString()).doesNotContain("Asha", "98765", "560001", "Rao");
        var bad = page.items().stream().filter(l -> l.orderStatus().equals("CONFIRMED")).findFirst().orElseThrow();
        assertThat(bad.shipState()).isNull();                                              // an unreadable snapshot is an unknown place, not an error
    }

    @Test
    void approved_returns_from_the_returns_module_are_added_once_and_a_changed_order_reappears_after_the_old_cursor() {
        var mine = order("DELIVERED", "2026-09-12T10:00:00Z", "{\"state\":\"KA\"}", "250.00", "0.00", line(BAT, 2, "100.00"), line(BALL, 1, "50.00"));
        APPROVED.add(new com.dsports.order.application.order.port.ApprovedReturnsPort.ApprovedReturn(mine, BAT, 1));
        var first = feed.execute(null, null, null, null, 100).block();
        var bat = first.items().stream().filter(l -> l.productId().equals(BAT) && l.orderId().equals(mine)).findFirst().orElseThrow();
        assertThat(bat.approvedReturnQuantity()).isEqualTo(1);
        var ball = first.items().stream().filter(l -> l.productId().equals(BALL) && l.orderId().equals(mine)).findFirst().orElseThrow();
        assertThat(ball.approvedReturnQuantity()).isZero();                                // nothing was returned for the ball
        var cursor = first.nextUpdatedAt();
        db.sql("UPDATE orders SET status = 'REFUNDED', updated_at = now() + interval '1 minute' WHERE id = :id").bind("id", mine).fetch().rowsUpdated().block();
        var delta = feed.execute(cursor, first.nextLineId(), null, null, 100).block();
        assertThat(delta.items()).hasSize(2).allSatisfy(l -> assertThat(l.orderStatus()).isEqualTo("REFUNDED"));      // both lines of the changed order come again
        APPROVED.clear();
    }

    @Test
    void returns_of_a_product_on_two_lines_of_one_order_are_attributed_to_the_first_line_only() {
        var twice = order("DELIVERED", "2026-09-10T10:00:00Z", "{\"state\":\"TN\"}", "200.00", "0.00", line(BAT, 1, "100.00"), line(BAT, 1, "100.00"));
        APPROVED.add(new com.dsports.order.application.order.port.ApprovedReturnsPort.ApprovedReturn(twice, BAT, 1));
        var lines = feed.execute(null, null, java.time.Instant.parse("2026-09-10T00:00:00Z"), java.time.Instant.parse("2026-09-11T00:00:00Z"), 100).block().items();
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().mapToInt(l -> l.approvedReturnQuantity()).sum()).isEqualTo(1);    // never counted twice
        APPROVED.clear();
    }

    @Test
    void paging_has_a_bounded_size_and_a_cursor_that_walks_every_line_once() {
        var seen = new java.util.HashSet<UUID>();
        Instant ts = null;
        UUID id = null;
        for (int i = 0; i < 10; i++) {
            var p = feed.execute(ts, id, null, null, 1).block();
            p.items().forEach(l -> seen.add(l.lineId()));
            ts = p.nextUpdatedAt();
            id = p.nextLineId();
            if (!p.hasMore()) break;
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(4);
        assertThat(feed.execute(null, null, null, null, 1_000_000).block().items()).hasSizeLessThanOrEqualTo(GetSalesFeedUseCase.MAX_LIMIT);
    }

    @Test
    void the_placement_window_bounds_the_feed() {
        var onlySeptember8 = feed.execute(null, null, Instant.parse("2026-09-08T00:00:00Z"), Instant.parse("2026-09-09T00:00:00Z"), 100).block();
        assertThat(onlySeptember8.items()).hasSize(1).allSatisfy(l -> assertThat(l.orderStatus()).isEqualTo("CANCELLED"));
    }
}
