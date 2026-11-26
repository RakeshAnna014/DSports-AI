package com.dsports.payment.application.payment.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Local consequences of a captured payment, executed in the same transaction that records the capture:
 * confirm the order, fulfil its stock reservations, complete the cart and record follow-up work (shipment,
 * notifications). If any step fails, the capture is not recorded and can be retried safely.
 */
public interface PaymentCompletionPort {

    Mono<Void> completeOrder(UUID orderId);

    /**
     * Completes whatever the payment was for and reports whether it still could be. {@code false} means the thing paid for was no
     * longer available (for example a held booking that expired or was cancelled): the capture is still recorded, the implementation has
     * already queued a full refund in the same transaction, and the caller must tell the customer clearly instead of reporting success.
     * The default suits orders, which are always completed or the whole capture fails and can be retried.
     */
    default Mono<Boolean> complete(UUID orderId, UUID paymentId) {
        return completeOrder(orderId).thenReturn(true);
    }
}
