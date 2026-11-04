package com.dsports.returns.infrastructure.persistence;

import com.dsports.returns.application.port.ApprovedReturnsReadPort;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.UUID;

public class ApprovedReturnsR2dbcReader implements ApprovedReturnsReadPort {

    private final DatabaseClient db;

    public ApprovedReturnsR2dbcReader(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public Flux<Approved> approvedFor(Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return Flux.empty();
        return db.sql("SELECT order_id, product_id, SUM(quantity) AS qty FROM return_requests WHERE status = 'APPROVED' AND order_id IN (:ids) GROUP BY order_id, product_id")
            .bind("ids", orderIds)
            .map((row, meta) -> new Approved(row.get("order_id", UUID.class), row.get("product_id", UUID.class), row.get("qty", Long.class))).all();
    }
}
