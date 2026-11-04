package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.application.order.port.SalesFeedPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class SalesFeedR2dbcAdapter implements SalesFeedPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatabaseClient db;

    public SalesFeedR2dbcAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<SalesLine> linesAfter(Instant afterUpdatedAt, UUID afterLineId, Instant placedFrom, Instant placedTo, int limit) {
        // Only placed orders (a cart or checkout that never became an order is not a sale). The status is passed through so the consumer applies the sales convention.
        // The source version is the order's update time, so a later confirmation, cancellation or delivery makes the line re-appear. Returns live in another module.
        StringBuilder where = new StringBuilder("o.placed_at IS NOT NULL");
        if (placedFrom != null) where.append(" AND o.placed_at >= :from");
        if (placedTo != null) where.append(" AND o.placed_at < :to");
        String sql = "SELECT * FROM ("
            + "SELECT oi.id AS line_id, o.id AS order_id, o.order_number, oi.product_id, oi.sku, oi.product_name, oi.quantity, oi.unit_price, oi.line_total, "
            + "CASE WHEN o.subtotal > 0 THEN ROUND(o.discount_amount * oi.line_total / o.subtotal, 2) ELSE 0 END AS allocated_discount, "
            + "o.currency, o.status, o.placed_at, o.cancelled_at, o.shipping_address_snapshot, o.updated_at AS source_updated_at "
            + "FROM order_items oi JOIN orders o ON o.id = oi.order_id WHERE " + where + ") t "
            + "WHERE (t.source_updated_at, t.line_id) > (:ts, :id) ORDER BY t.source_updated_at, t.line_id LIMIT " + Math.max(1, limit);
        DatabaseClient.GenericExecuteSpec spec = db.sql(sql).bind("ts", OffsetDateTime.ofInstant(afterUpdatedAt, ZoneOffset.UTC)).bind("id", afterLineId);
        if (placedFrom != null) spec = spec.bind("from", OffsetDateTime.ofInstant(placedFrom, ZoneOffset.UTC));
        if (placedTo != null) spec = spec.bind("to", OffsetDateTime.ofInstant(placedTo, ZoneOffset.UTC));
        return spec.map((row, meta) -> {
            String[] place = place(row.get("shipping_address_snapshot", String.class));
            int qty = row.get("quantity", Integer.class);
            return new SalesLine(row.get("line_id", UUID.class), row.get("order_id", UUID.class), row.get("order_number", String.class), row.get("product_id", UUID.class),
                row.get("sku", String.class), row.get("product_name", String.class), qty, row.get("unit_price", BigDecimal.class), row.get("line_total", BigDecimal.class),
                row.get("allocated_discount", BigDecimal.class), row.get("currency", String.class), row.get("status", String.class),
                instant(row.get("placed_at", OffsetDateTime.class)), instant(row.get("cancelled_at", OffsetDateTime.class)), 0,
                place[0], place[1], place[2], instant(row.get("source_updated_at", OffsetDateTime.class)));
        }).all();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    /** City, state and country only. The snapshot also holds a name, phone, street and postal code; none of those leave this module. */
    static String[] place(String snapshot) {
        String[] out = new String[3];
        if (snapshot == null || snapshot.isBlank()) return out;
        try {
            JsonNode n = JSON.readTree(snapshot);
            out[0] = text(n, "city");
            out[1] = text(n, "state");
            out[2] = text(n, "country");
        } catch (Exception ignored) {
            // an unreadable snapshot just has no place: the consumer reports that line as having an unknown region
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
