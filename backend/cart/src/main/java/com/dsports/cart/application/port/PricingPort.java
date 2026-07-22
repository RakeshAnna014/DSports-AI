package com.dsports.cart.application.port;

import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.UUID;

public interface PricingPort {
    Mono<ActivePriceResult> getActivePrice(UUID productId);

    record ActivePriceResult(
        UUID productId,
        BigDecimal unitPrice,
        String currency
    ) {}
}
