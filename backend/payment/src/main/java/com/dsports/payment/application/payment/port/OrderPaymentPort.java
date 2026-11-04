package com.dsports.payment.application.payment.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/** What payment needs to know about, and do to, the order it pays for. */
public interface OrderPaymentPort {

    /** Fails with ORDER_NOT_FOUND / ORDER_NOT_OWNED_BY_USER when the caller may not pay for the order. */
    Mono<OrderData> getOrderData(UUID orderId, UUID userId);

    /** Marks the order REFUNDED once its payment has been refunded in full. */
    Mono<Void> markOrderRefunded(UUID orderId);

    record OrderData(
        UUID orderId,
        UUID userId,
        String orderNumber,
        BigDecimal grandTotal,
        String currency,
        String status
    ) {
        public boolean awaitingPayment() {
            return "PENDING_PAYMENT".equals(status);
        }

        public boolean paid() {
            return "CONFIRMED".equals(status) || "PROCESSING".equals(status)
                || "SHIPPED".equals(status) || "DELIVERED".equals(status);
        }
    }
}
