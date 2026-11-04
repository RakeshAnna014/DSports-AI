package com.dsports.order.application.order.port;

import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only feed of sold order lines for sales planning. One row per order line with the order status, cancellation and the order's discount allocated to the line, so a consumer can
 * apply its own, documented sales convention. Approved returns belong to the returns module and are added by {@link ApprovedReturnsPort}. The feed carries NO customer identity:
 * only the delivery city, state and country of the order (the planning region), never a name, phone, street or postal code.
 */
public interface SalesFeedPort {

    record SalesLine(UUID lineId, UUID orderId, String orderNumber, UUID productId, String sku, String productName, int quantity, BigDecimal unitPrice,
                     BigDecimal lineTotal, BigDecimal allocatedDiscount, String currency, String orderStatus, Instant placedAt, Instant cancelledAt,
                     int approvedReturnQuantity, String shipCity, String shipState, String shipCountry, Instant sourceUpdatedAt) {

        public SalesLine withApprovedReturnQuantity(int returned) {
            return new SalesLine(lineId, orderId, orderNumber, productId, sku, productName, quantity, unitPrice, lineTotal, allocatedDiscount, currency, orderStatus, placedAt, cancelledAt,
                Math.min(Math.max(0, returned), quantity), shipCity, shipState, shipCountry, sourceUpdatedAt);
        }
    }

    /**
     * Lines whose order was updated after the cursor, oldest first. {@code placedFrom} and {@code placedTo} (exclusive) optionally bound the placement time. A changed order
     * (confirmed, cancelled, delivered) therefore re-appears.
     */
    Flux<SalesLine> linesAfter(Instant afterUpdatedAt, UUID afterLineId, Instant placedFrom, Instant placedTo, int limit);
}
