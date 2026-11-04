package com.dsports.order.infrastructure.promotion.persistence;

import com.dsports.order.application.promotion.port.PromotionRepository;
import com.dsports.order.domain.promotion.model.Promotion;
import com.dsports.order.domain.promotion.model.Promotion.DiscountType;
import com.dsports.order.domain.promotion.model.Promotion.ScopeType;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * R2DBC implementation of {@link PromotionRepository}. Usage counts ignore cancelled orders, so cancelling an unpaid order frees
 * the customer's use of the offer. {@link #lockById} is only meaningful inside the order transaction.
 */
public class PromotionR2dbcAdapter implements PromotionRepository {

    private static final String COLUMNS = "id, code, title, description, discount_type, discount_value, max_discount_amount, min_order_amount, "
        + "scope_type, valid_from, valid_until, per_customer_limit, total_usage_limit, active, demo";

    private final DatabaseClient db;

    public PromotionR2dbcAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Mono<Promotion> findByCode(String code) {
        return db.sql("SELECT " + COLUMNS + " FROM promotions WHERE UPPER(code) = UPPER(:code)")
            .bind("code", code).map((row, meta) -> read(row)).one().flatMap(this::withScopes);
    }

    @Override
    public Flux<Promotion> findActive() {
        return db.sql("SELECT " + COLUMNS + " FROM promotions WHERE active = TRUE ORDER BY code")
            .map((row, meta) -> read(row)).all().concatMap(this::withScopes);
    }

    @Override
    public Mono<Promotion> lockById(UUID promotionId) {
        return db.sql("SELECT " + COLUMNS + " FROM promotions WHERE id = :id FOR UPDATE")
            .bind("id", promotionId).map((row, meta) -> read(row)).one().flatMap(this::withScopes);
    }

    @Override
    public Mono<Long> countCustomerRedemptions(UUID promotionId, UUID customerId, UUID exceptCheckoutId) {
        return db.sql("SELECT COUNT(*) AS n FROM promotion_redemptions r LEFT JOIN orders o ON o.id = r.order_id "
                + "WHERE r.promotion_id = :p AND r.customer_id = :c AND r.checkout_id <> :x AND (o.status IS NULL OR o.status <> 'CANCELLED')")
            .bind("p", promotionId).bind("c", customerId).bind("x", exceptCheckoutId)
            .map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> countTotalRedemptions(UUID promotionId, UUID exceptCheckoutId) {
        return db.sql("SELECT COUNT(*) AS n FROM promotion_redemptions r LEFT JOIN orders o ON o.id = r.order_id "
                + "WHERE r.promotion_id = :p AND r.checkout_id <> :x AND (o.status IS NULL OR o.status <> 'CANCELLED')")
            .bind("p", promotionId).bind("x", exceptCheckoutId)
            .map((row, meta) -> row.get("n", Long.class)).one().defaultIfEmpty(0L);
    }

    @Override
    public Mono<UUID> findOrderIdByCheckout(UUID checkoutId) {
        return db.sql("SELECT id FROM orders WHERE checkout_id = :c").bind("c", checkoutId)
            .map((row, meta) -> row.get("id", UUID.class)).one();
    }

    @Override
    public Mono<Void> insertRedemption(UUID promotionId, UUID customerId, UUID checkoutId, UUID orderId, BigDecimal discountAmount) {
        return db.sql("INSERT INTO promotion_redemptions (id, promotion_id, customer_id, checkout_id, order_id, discount_amount) "
                + "VALUES (:id, :p, :c, :co, :o, :d)")
            .bind("id", UUID.randomUUID()).bind("p", promotionId).bind("c", customerId).bind("co", checkoutId)
            .bind("o", orderId).bind("d", discountAmount)
            .fetch().rowsUpdated().then();
    }

    /** Reads every column inside the mapping function: a Row must not be touched after it returns. Scopes are added afterwards. */
    private static Promotion read(Row row) {
        return new Promotion(
            row.get("id", UUID.class),
            row.get("code", String.class),
            row.get("title", String.class),
            row.get("description", String.class),
            DiscountType.valueOf(row.get("discount_type", String.class)),
            row.get("discount_value", BigDecimal.class),
            row.get("max_discount_amount", BigDecimal.class),
            row.get("min_order_amount", BigDecimal.class),
            ScopeType.valueOf(row.get("scope_type", String.class)),
            Set.of(),
            row.get("valid_from", Instant.class),
            row.get("valid_until", Instant.class),
            row.get("per_customer_limit", Integer.class),
            row.get("total_usage_limit", Integer.class),
            Boolean.TRUE.equals(row.get("active", Boolean.class)),
            Boolean.TRUE.equals(row.get("demo", Boolean.class)));
    }

    private Mono<Promotion> withScopes(Promotion p) {
        return db.sql("SELECT scope_id FROM promotion_scopes WHERE promotion_id = :id").bind("id", p.id())
            .map((r, meta) -> r.get("scope_id", UUID.class)).all().collect(HashSet<UUID>::new, HashSet::add)
            .map(scopeIds -> new Promotion(p.id(), p.code(), p.title(), p.description(), p.discountType(), p.discountValue(), p.maxDiscountAmount(),
                p.minOrderAmount(), p.scopeType(), scopeIds, p.validFrom(), p.validUntil(), p.perCustomerLimit(), p.totalUsageLimit(), p.active(), p.demo()));
    }
}