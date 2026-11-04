package com.dsports.order.infrastructure.order.persistence;

import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.AddressSnapshot;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.order.infrastructure.order.persistence.repository.OrderR2dbcRepositoryAdapter;
import com.dsports.order.infrastructure.order.persistence.repository.SpringR2dbcOrderItemRepository;
import com.dsports.order.infrastructure.order.persistence.repository.SpringR2dbcOrderRepository;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** read → version N → update → N+1; a second writer still holding version N must fail instead of overwriting. */
@Tag("integration")
class OrderOptimisticLockingIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static OrderR2dbcRepositoryAdapter orders;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").load().migrate();
        var connectionFactory = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":"
            + POSTGRES.getPassword() + "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/"
            + POSTGRES.getDatabaseName());
        var factory = new R2dbcRepositoryFactory(new R2dbcEntityTemplate(connectionFactory));
        orders = new OrderR2dbcRepositoryAdapter(factory.getRepository(SpringR2dbcOrderRepository.class),
            factory.getRepository(SpringR2dbcOrderItemRepository.class), new R2dbcTransactionManager(connectionFactory));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void newOrderIsInsertedAndEachUpdateIncrementsTheVersion() {
        var order = newOrder();
        orders.save(order).block();
        var loaded = orders.findById(order.getId()).block();
        assertThat(loaded.getVersion()).isEqualTo(1);
        assertThat(loaded.getItems()).hasSize(1);

        loaded.confirm();
        orders.save(loaded).block();

        var reloaded = orders.findById(order.getId()).block();
        assertThat(reloaded.getVersion()).isEqualTo(2);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void staleWriterIsRejectedInsteadOfOverwriting() {
        var order = newOrder();
        orders.save(order).block();
        var first = orders.findById(order.getId()).block();
        var second = orders.findById(order.getId()).block();

        first.confirm();
        orders.save(first).block();

        second.cancel();
        StepVerifier.create(orders.save(second))
            .expectErrorMatches(e -> e instanceof OrderDomainException ode
                && ode.getErrorCode() == OrderErrorCode.OPTIMISTIC_LOCKING_CONFLICT)
            .verify();
        assertThat(orders.findById(order.getId()).block().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void secondOrderForTheSameCheckoutIsRejectedByTheDatabase() {
        var checkoutId = UUID.randomUUID();
        orders.save(newOrder(checkoutId, 2)).block();

        StepVerifier.create(orders.save(newOrder(checkoutId, 3)))
            .expectErrorMatches(e -> e instanceof OrderDomainException ode
                && ode.getErrorCode() == OrderErrorCode.DUPLICATE_ORDER)
            .verify();
    }

    @Test
    void orderNumbersComeFromASequence() {
        var a = orders.nextOrderSequence().block();
        var b = orders.nextOrderSequence().block();
        assertThat(b).isEqualTo(a + 1);
    }

    private static Order newOrder() {
        return newOrder(UUID.randomUUID(), (long) (Math.random() * 1_000_000));
    }

    private static Order newOrder(UUID checkoutId, long sequence) {
        var id = OrderId.generate();
        var item = OrderItem.create(OrderItemId.generate(), id, UUID.randomUUID(), "Bat", "SKU-1", 1,
            new BigDecimal("100.00"), new BigDecimal("100.00"), null);
        var address = new AddressSnapshot("1 MG Road", null, "Bengaluru", "KA", "IN", "560001", "A B", "999");
        var order = Order.place(id, OrderNumber.generate(sequence), UUID.randomUUID(), checkoutId, address, address,
            List.of(item), new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("100.00"), "INR");
        order.markPendingPayment();
        return order;
    }
}
