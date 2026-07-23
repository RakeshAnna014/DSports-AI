package com.dsports.order.application.checkout.port;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AddressPort {
    Mono<Boolean> addressBelongsToCustomer(UUID addressId, UUID customerId);
}
