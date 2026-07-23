package com.dsports.order.application.order.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface CartCheckoutPort {
    Mono<Void> markCartAsCheckedOut(UUID cartId);
}
