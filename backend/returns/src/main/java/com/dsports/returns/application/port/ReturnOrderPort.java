package com.dsports.returns.application.port;

import com.dsports.returns.domain.policy.ReturnPolicy;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Read access to the customer's own order. Empty when the order does not exist or belongs to someone else. */
public interface ReturnOrderPort {

    Mono<ReturnPolicy.OrderFacts> findOrderOfCustomer(UUID orderId, UUID userId);
}
