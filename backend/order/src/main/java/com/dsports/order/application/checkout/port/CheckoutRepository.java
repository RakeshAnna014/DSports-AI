package com.dsports.order.application.checkout.port;

import com.dsports.order.domain.checkout.model.Checkout;
import com.dsports.order.domain.checkout.model.CheckoutId;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CheckoutRepository {
    Mono<Checkout> findById(CheckoutId id);
    Mono<Checkout> findByCustomerId(UUID customerId);
    Mono<Boolean> existsActiveCheckout(UUID customerId);
    Mono<Void> save(Checkout checkout);
    Mono<Void> delete(CheckoutId id);
}
