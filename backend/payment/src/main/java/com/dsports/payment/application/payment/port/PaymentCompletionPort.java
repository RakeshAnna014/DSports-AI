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
}
