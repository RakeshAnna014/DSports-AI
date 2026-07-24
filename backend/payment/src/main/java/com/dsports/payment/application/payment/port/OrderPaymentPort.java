package com.dsports.payment.application.payment.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface OrderPaymentPort {
    Mono<OrderData> getOrderData(UUID orderId, UUID userId);
    Mono<Void> markOrderAsPaid(UUID orderId);

    record OrderData(
        UUID orderId,
        UUID userId,
        BigDecimal grandTotal,
        String currency,
        boolean alreadyPaid
    ) {}
}
