package com.dsports.order.infrastructure.promotion.persistence;

import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.promotion.usecase.CheckoutPromotionsUseCase;
import com.dsports.order.application.promotion.usecase.RedeemPromotionUseCase;
import com.dsports.order.domain.checkout.exception.CheckoutDomainException;
import com.dsports.order.domain.checkout.exception.CheckoutErrorCode;
import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.checkout.model.CheckoutItem;
import com.dsports.order.domain.checkout.model.CheckoutItemId;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Promotions against a real PostgreSQL with the real migration (V47 and its demo seed): listing, applying, removing,
 * ownership, usage limits (cancelled orders free a use) and the row-lock that serialises concurrent redemptions.
 */
@Tag("integration")
class PromotionIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static DatabaseClient db;
    private static CheckoutPromotionsUseCase promotions;
    private static RedeemPromotionUseCase redeemer;
    private static TransactionalOperator tx;
    private static final Map<UUID, Checkout> STORE = new ConcurrentHashMap<>();
    private static final UUID CRICKET_SPORT = UUID.randomUUID();
    private static final UUID FOOTBALL_SPORT = UUID.randomUUID();
    private static final UUID BAT = UUID.randomUUID();
    private static final UUID BALL = UUID.randomUUID();

    private static final CheckoutRepository CHECKOUTS = new CheckoutRepository() {
        public Mono<Checkout> findById(CheckoutId id) { return Mono.justOrEmpty(STORE.get(id.value())); }
        public Mono<Checkout> findByCustomerId(UUID customerId) { return Mono.empty(); }
        public Mono<Boolean> existsActiveCheckout(UUID customerId) { return Mono.just(false); }
        public Mono<Void> save(Checkout checkout) { STORE.put(checkout.getId().value(), checkout); return Mono.empty(); }
        public Mono<Void> delete(CheckoutId id) { STORE.remove(id.value()); return Mono.empty(); }
    };

    @BeforeAll
    static void start() {
        POSTGRES.start();
        var url = POSTGRES.getJdbcUrl();
        var flyway = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).locations("classpath:db/migration")
            .baselineOnMigrate(true).baselineVersion("0");
        // the catalogue tables belong to another module; create the minimum the promotion scope needs before V47 seeds its scope
        try (var c = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword()); var s = c.createStatement()) {
            s.execute("CREATE TABLE sports (id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            s.execute("CREATE TABLE products (id UUID PRIMARY KEY, category_id UUID NOT NULL, sport_id UUID NOT NULL)");
            s.execute("INSERT INTO sports VALUES ('" + CRICKET_SPORT + "', 'Cricket'), ('" + FOOTBALL_SPORT + "', 'Football')");
            s.execute("INSERT INTO products VALUES ('" + BAT + "', gen_random_uuid(), '" + CRICKET_SPORT + "'), ('" + BALL + "', gen_random_uuid(), '" + FOOTBALL_SPORT + "')");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        flyway.load().migrate();
        var cf = ConnectionFactories.get("r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword() + "@"
            + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + POSTGRES.getDatabaseName());
        db = DatabaseClient.create(cf);
        tx = TransactionalOperator.create(new R2dbcTransactionManager(cf));
        var repo = new PromotionR2dbcAdapter(db);
        var scopes = new ProductScopeR2dbcAdapter(db);
        promotions = new CheckoutPromotionsUseCase(CHECKOUTS, repo, scopes);
        redeemer = new RedeemPromotionUseCase(CHECKOUTS, repo, scopes);
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    private static Checkout checkout(UUID customer, Object... productAndTotal) {
        var id = CheckoutId.generate();
        var items = new java.util.ArrayList<CheckoutItem>();
        for (int i = 0; i < productAndTotal.length; i += 2) {
            var product = (UUID) productAndTotal[i];
            var total = new BigDecimal((String) productAndTotal[i + 1]);
            items.add(new CheckoutItem(CheckoutItemId.generate(), id, product.toString(), "p", "SKU", 1, total, total, null, Instant.now()));
        }
        var checkout = Checkout.create(id, customer, UUID.randomUUID(), items);
        STORE.put(id.value(), checkout);
        return checkout;
    }

    private static UUID orderFor(Checkout checkout, String status) {
        var orderId = UUID.randomUUID();
        db.sql("INSERT INTO orders (id, order_number, user_id, checkout_id, status) VALUES (:id, :n, :u, :c, :s)")
            .bind("id", orderId).bind("n", "ORD-" + orderId.toString().substring(0, 8)).bind("u", checkout.getCustomerId())
            .bind("c", checkout.getId().value()).bind("s", status).fetch().rowsUpdated().block();
        return orderId;
    }

    private static CheckoutDomainException failure(Mono<?> mono) {
        try {
            mono.block();
        } catch (CheckoutDomainException e) {
            return e;
        }
        throw new AssertionError("expected a CheckoutDomainException");
    }

    @Test
    void listsCurrentOffersWithThisCartsAuthoritativeDiscountAndReasonsWhenNotEligible() {
        var customer = UUID.randomUUID();
        var c = checkout(customer, BAT, "2000.00");
        var offers = promotions.list(c.getId().value(), customer).block();
        assertThat(offers).extracting(o -> o.code()).doesNotContain("OLDFEST5");                 // expired offers are not listed
        var byCode = offers.stream().collect(java.util.stream.Collectors.toMap(o -> o.code(), o -> o));
        assertThat(byCode.get("WELCOME10").eligible()).isTrue();
        assertThat(byCode.get("WELCOME10").discountAmount()).isEqualByComparingTo("200.00");
        assertThat(byCode.get("CRICKET15").discountAmount()).isEqualByComparingTo("300.00");      // 15% of the cricket line
        assertThat(byCode.get("FLAT200").eligible()).isFalse();
        assertThat(byCode.get("FLAT200").ineligibleReason()).contains("1000.00");
        assertThat(byCode.values()).allMatch(o -> o.demo());
        assertThat(offers.get(0).eligible()).isTrue();                                          // eligible first, best discount first
        assertThat(offers.get(0).code()).isEqualTo("CRICKET15");
    }

    @Test
    void aFootballOnlyCartIsNotEligibleForTheCricketOffer() {
        var customer = UUID.randomUUID();
        var c = checkout(customer, BALL, "2000.00");
        assertThat(failure(promotions.apply(c.getId().value(), customer, "CRICKET15")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_NOT_APPLICABLE);
    }

    @Test
    void applyingRecalculatesTotalsIsIdempotentAndCanBeRemoved() {
        var customer = UUID.randomUUID();
        var c = checkout(customer, BAT, "2000.00");
        var before = c.getTotalAmount();                                                        // 2000 + 18% tax
        var applied = promotions.apply(c.getId().value(), customer, "  welcome10 ").block();   // case and whitespace do not matter
        assertThat(applied.promotionCode()).isEqualTo("WELCOME10");
        assertThat(applied.discountAmount()).isEqualByComparingTo("200.00");
        assertThat(applied.taxAmount()).isEqualByComparingTo("360.00");                         // existing rule: tax stays on the pre-discount subtotal
        assertThat(applied.totalAmount()).isEqualByComparingTo(before.subtract(new BigDecimal("200.00")));
        var again = promotions.apply(c.getId().value(), customer, "WELCOME10").block();         // duplicate application: same result, not 2x
        assertThat(again.discountAmount()).isEqualByComparingTo("200.00");
        assertThat(again.totalAmount()).isEqualByComparingTo(applied.totalAmount());
        var removed = promotions.remove(c.getId().value(), customer).block();
        assertThat(removed.promotionCode()).isNull();
        assertThat(removed.discountAmount()).isEqualByComparingTo("0");
        assertThat(removed.totalAmount()).isEqualByComparingTo(before);
        assertThat(promotions.remove(c.getId().value(), customer).block().discountAmount()).isEqualByComparingTo("0");   // removing twice is a no-op
    }

    @Test
    void oneOfferPerCheckoutAndRejectionsCarryTheRightCode() {
        var customer = UUID.randomUUID();
        var c = checkout(customer, BAT, "2000.00");
        promotions.apply(c.getId().value(), customer, "WELCOME10").block();
        assertThat(failure(promotions.apply(c.getId().value(), customer, "CRICKET15")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_ALREADY_APPLIED);
        assertThat(promotions.remove(c.getId().value(), customer).block().discountAmount()).isEqualByComparingTo("0");
        assertThat(failure(promotions.apply(c.getId().value(), customer, "NOSUCHCODE")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_NOT_FOUND);
        assertThat(failure(promotions.apply(c.getId().value(), customer, "OLDFEST5")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_EXPIRED);
        assertThat(failure(promotions.apply(c.getId().value(), customer, "FLAT200")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_MIN_ORDER_NOT_MET);
        assertThat(c.getDiscountAmount()).isEqualByComparingTo("0");                             // failures change nothing
    }

    @Test
    void aCustomerCanNotTouchSomeoneElsesCheckout() {
        var owner = UUID.randomUUID();
        var c = checkout(owner, BAT, "2000.00");
        var intruder = UUID.randomUUID();
        assertThat(failure(promotions.list(c.getId().value(), intruder)).getErrorCode()).isEqualTo(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER);
        assertThat(failure(promotions.apply(c.getId().value(), intruder, "WELCOME10")).getErrorCode()).isEqualTo(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER);
        assertThat(failure(promotions.remove(c.getId().value(), intruder)).getErrorCode()).isEqualTo(CheckoutErrorCode.CHECKOUT_NOT_OWNED_BY_CUSTOMER);
        assertThat(c.getPromotionCode()).isNull();
    }

    @Test
    void aValidatedCheckoutCanNoLongerChangeItsPromotion() {
        var customer = UUID.randomUUID();
        var c = checkout(customer, BAT, "2000.00");
        c.selectShippingAddress(UUID.randomUUID());
        c.selectDeliveryMethod(com.dsports.order.domain.checkout.model.DeliveryMethod.fromCode("STANDARD"));
        c.validate();
        assertThat(failure(promotions.apply(c.getId().value(), customer, "WELCOME10")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_NOT_EDITABLE);
    }

    @Test
    void theUsageLimitIsPerCustomerAndACancelledOrderFreesTheUse() {
        var customer = UUID.randomUUID();
        var first = checkout(customer, BAT, "2000.00");
        promotions.apply(first.getId().value(), customer, "WELCOME10").block();                  // limit: once per customer
        var firstOrder = orderFor(first, "PENDING_PAYMENT");
        redeemer.redeem(first.getId().value()).block();
        var second = checkout(customer, BAT, "2000.00");
        assertThat(failure(promotions.apply(second.getId().value(), customer, "WELCOME10")).getErrorCode()).isEqualTo(CheckoutErrorCode.PROMOTION_LIMIT_REACHED);
        var other = UUID.randomUUID();
        assertThat(promotions.apply(checkout(other, BAT, "2000.00").getId().value(), other, "WELCOME10").block().promotionCode())
            .isEqualTo("WELCOME10");                                                             // another customer is unaffected
        db.sql("UPDATE orders SET status = 'CANCELLED' WHERE id = :id").bind("id", firstOrder).fetch().rowsUpdated().block();
        assertThat(promotions.apply(second.getId().value(), customer, "WELCOME10").block().promotionCode()).isEqualTo("WELCOME10");
    }

    @Test
    void redemptionIsIdempotentPerCheckoutAndNoOpWithoutAPromotion() {
        var customer = UUID.randomUUID();
        var plain = checkout(customer, BAT, "2000.00");
        orderFor(plain, "PENDING_PAYMENT");
        redeemer.redeem(plain.getId().value()).block();                                          // nothing applied: nothing recorded
        assertThat(count("SELECT COUNT(*) AS n FROM promotion_redemptions WHERE checkout_id = '" + plain.getId().value() + "'")).isZero();
        var c = checkout(customer, BAT, "2000.00");
        promotions.apply(c.getId().value(), customer, "CRICKET15").block();
        orderFor(c, "PENDING_PAYMENT");
        redeemer.redeem(c.getId().value()).block();
        assertThat(count("SELECT COUNT(*) AS n FROM promotion_redemptions WHERE checkout_id = '" + c.getId().value() + "'")).isEqualTo(1);
        assertThatThrownBy(() -> redeemer.redeem(c.getId().value()).block()).isInstanceOf(Exception.class);   // unique (promotion, checkout)
    }

    @Test
    void concurrentOrdersCanNotBothPassTheSameOverallLimit() {
        db.sql("INSERT INTO promotions (id, code, title, description, discount_type, discount_value, total_usage_limit) "
            + "VALUES (:id, 'ONLYONE', 't', 'DEMO OFFER (synthetic) test', 'FIXED', 50, 1)").bind("id", UUID.randomUUID()).fetch().rowsUpdated().block();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var ca = checkout(a, BAT, "1000.00");
        var cb = checkout(b, BAT, "1000.00");
        promotions.apply(ca.getId().value(), a, "ONLYONE").block();                              // both pass the (non-binding) check at apply time
        promotions.apply(cb.getId().value(), b, "ONLYONE").block();
        orderFor(ca, "PENDING_PAYMENT");
        orderFor(cb, "PENDING_PAYMENT");
        var results = Mono.zip(
            tx.transactional(redeemer.redeem(ca.getId().value())).materialize(),
            tx.transactional(redeemer.redeem(cb.getId().value())).materialize()).block();
        var failures = List.of(results.getT1(), results.getT2()).stream().filter(s -> s.isOnError()).toList();
        assertThat(failures).hasSize(1);                                                         // exactly one order wins
        assertThat(failures.get(0).getThrowable()).isInstanceOf(OrderDomainException.class)
            .extracting(t -> ((OrderDomainException) t).getErrorCode()).isEqualTo(OrderErrorCode.PROMOTION_NO_LONGER_VALID);
        assertThat(count("SELECT COUNT(*) AS n FROM promotion_redemptions r JOIN promotions p ON p.id = r.promotion_id WHERE p.code = 'ONLYONE'")).isEqualTo(1);
    }

    private static long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get("n", Long.class)).one().block();
    }
}
