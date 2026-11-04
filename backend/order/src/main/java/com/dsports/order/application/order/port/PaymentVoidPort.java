package com.dsports.order.application.order.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

/** Cancels payments that were started but not captured, so a cancelled order cannot be charged later. */
public interface PaymentVoidPort {

    Mono<Void> voidOpenPayments(UUID orderId);
}
