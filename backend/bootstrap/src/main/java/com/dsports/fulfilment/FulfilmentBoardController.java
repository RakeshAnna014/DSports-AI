package com.dsports.fulfilment;

import com.dsports.shipment.domain.model.ShipmentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only board for the demo fulfilment console: orders with their payment and shipment state and the shipment steps that are valid
 * next. The valid steps come from {@link ShipmentStatus#canTransitionTo} (the domain state machine), never from the client, and the
 * board changes nothing: every change goes through the existing admin order / shipment endpoints, which enforce the same rules,
 * the ADMIN role and (for shipments) the order-follows-shipment synchronisation. Customer e-mail addresses are masked.
 */
@RestController
@RequestMapping(path = "/api/v1/admin/fulfilment", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Fulfilment console")
@SecurityRequirement(name = "bearer-jwt")
public class FulfilmentBoardController {

    private static final Set<String> ORDER_STATUSES = Set.of("PENDING_PAYMENT", "CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED", "CREATED");
    private static final List<String> DEFAULT_STATUSES = List.of("CONFIRMED", "PROCESSING", "SHIPPED");
    private static final int MAX_LIMIT = 100;

    private final DatabaseClient db;

    public FulfilmentBoardController(DatabaseClient db) {
        this.db = db;
    }

    public record FulfilmentRow(
        UUID orderId, String orderNumber, String orderStatus, BigDecimal grandTotal, String currency, Instant placedAt, int items,
        String customer, String paymentStatus, boolean paid,
        UUID shipmentId, String shipmentNumber, String trackingNumber, String shipmentStatus, String courierProvider,
        boolean canCreateShipment, List<String> nextShipmentStatuses) {}

    @GetMapping("/orders")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Orders and shipments for the fulfilment console (ADMIN)",
               description = "Newest first. status = comma separated order statuses (default CONFIRMED,PROCESSING,SHIPPED). "
                   + "nextShipmentStatuses lists the shipment transitions the domain allows now; change them through PUT /api/v1/shipments/admin/{id}/status.")
    public Mono<List<FulfilmentRow>> board(@RequestParam(required = false) List<String> status, @RequestParam(defaultValue = "50") int limit) {
        var statuses = status == null || status.isEmpty() ? DEFAULT_STATUSES : status.stream().flatMap(s -> Arrays.stream(s.split(","))).map(String::trim).toList();
        if (!ORDER_STATUSES.containsAll(statuses)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown order status. Allowed: " + ORDER_STATUSES));
        }
        var capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return db.sql("SELECT o.id, o.order_number, o.status, o.grand_total, o.currency, o.placed_at, c.email, "
                + "(SELECT COUNT(*) FROM order_items i WHERE i.order_id = o.id) AS items, "
                + "(SELECT p.status FROM payments p WHERE p.order_id = o.id ORDER BY p.created_at DESC LIMIT 1) AS payment_status, "
                + "s.id AS shipment_id, s.shipment_number, s.tracking_number, s.status AS shipment_status, s.courier_provider "
                + "FROM orders o LEFT JOIN customers c ON c.id = o.user_id LEFT JOIN shipments s ON s.order_id = o.id "
                + "WHERE o.status IN (:statuses) ORDER BY o.placed_at DESC NULLS LAST LIMIT " + capped)
            .bind("statuses", statuses)
            .map((row, meta) -> toRow(
                row.get("id", UUID.class), row.get("order_number", String.class), row.get("status", String.class), row.get("grand_total", BigDecimal.class),
                row.get("currency", String.class), row.get("placed_at", Instant.class), row.get("items", Long.class), row.get("email", String.class),
                row.get("payment_status", String.class), row.get("shipment_id", UUID.class), row.get("shipment_number", String.class),
                row.get("tracking_number", String.class), row.get("shipment_status", String.class), row.get("courier_provider", String.class)))
            .all().collectList();
    }

    static FulfilmentRow toRow(UUID orderId, String orderNumber, String orderStatus, BigDecimal total, String currency, Instant placedAt, Long items,
                               String email, String paymentStatus, UUID shipmentId, String shipmentNumber, String tracking, String shipmentStatus, String courier) {
        var paid = "SUCCESS".equals(paymentStatus) || "PARTIALLY_REFUNDED".equals(paymentStatus);
        var next = shipmentStatus == null ? List.<String>of()
            : Arrays.stream(ShipmentStatus.values()).filter(ShipmentStatus.valueOf(shipmentStatus)::canTransitionTo).map(Enum::name).toList();
        var canCreate = shipmentId == null && paid && ("CONFIRMED".equals(orderStatus) || "PROCESSING".equals(orderStatus));
        return new FulfilmentRow(orderId, orderNumber, orderStatus, total, currency, placedAt, items == null ? 0 : items.intValue(), maskEmail(email),
            paymentStatus, paid, shipmentId, shipmentNumber, tracking, shipmentStatus, courier, canCreate, next);
    }

    static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return null;
        }
        var at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
