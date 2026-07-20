package com.dsports.pricing.application.port;

import com.dsports.pricing.domain.model.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PriceRepository {
    Mono<Price> findById(PriceId id);
    Flux<Price> findByProductId(ProductId productId);
    Flux<Price> findAll();
    Mono<Boolean> existsByProductIdAndCurrencyAndStatus(ProductId productId, Currency currency, PriceStatus status);
    Mono<Void> deactivateActivePrices(ProductId productId, Currency currency, PriceId excludeId);
    Mono<Void> save(Price price);
}
