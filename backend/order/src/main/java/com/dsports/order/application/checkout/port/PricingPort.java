package com.dsports.order.application.checkout.port;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

public interface PricingPort {
    Mono<PriceResult> getActivePrice(UUID productId);

    record PriceResult(UUID productId, BigDecimal sellingPrice, String currency) {}
}
