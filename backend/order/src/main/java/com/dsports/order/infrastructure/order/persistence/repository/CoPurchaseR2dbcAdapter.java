package com.dsports.order.infrastructure.order.persistence.repository;

import com.dsports.order.application.order.port.CoPurchasePort;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.util.UUID;

public class CoPurchaseR2dbcAdapter implements CoPurchasePort {

    private final DatabaseClient db;

    public CoPurchaseR2dbcAdapter(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<CoPurchase> coPurchased(UUID productId, int minOrders, int limit) {
        // Distinct orders (not quantities) that contain both products. Cancelled orders are not evidence of what people keep buying.
        return db.sql("SELECT b.product_id AS pid, COUNT(DISTINCT a.order_id) AS n FROM order_items a "
                + "JOIN order_items b ON b.order_id = a.order_id AND b.product_id <> a.product_id "
                + "JOIN orders o ON o.id = a.order_id AND o.status <> 'CANCELLED' "
                + "WHERE a.product_id = :p GROUP BY b.product_id HAVING COUNT(DISTINCT a.order_id) >= :min "
                + "ORDER BY n DESC, b.product_id LIMIT " + limit)
            .bind("p", productId).bind("min", (long) minOrders)
            .map((row, meta) -> new CoPurchase(row.get("pid", UUID.class), row.get("n", Long.class))).all();
    }
}
