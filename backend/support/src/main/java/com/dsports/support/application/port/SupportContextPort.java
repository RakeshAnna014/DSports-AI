package com.dsports.support.application.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the support module is allowed to know about the rest of DSports, owner-scoped. Every method answers "not found"
 * (empty / false) for a record that belongs to someone else, exactly like it does for one that does not exist.
 */
public interface SupportContextPort {

    record OrderFacts(UUID id, String orderNumber, String status, BigDecimal grandTotal, String currency, Instant placedAt,
                      String shipmentStatus, Instant deliveredAt, int successfulPayments, int paymentAttempts,
                      Map<UUID, String> productNames) {}

    Mono<OrderFacts> orderOfCustomer(UUID orderId, UUID userId);

    Mono<Boolean> paymentBelongsTo(UUID paymentId, UUID userId);

    Mono<Boolean> returnBelongsTo(UUID returnId, UUID userId);
}
