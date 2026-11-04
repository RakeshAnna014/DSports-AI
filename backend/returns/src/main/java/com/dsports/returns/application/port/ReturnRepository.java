package com.dsports.returns.application.port;

import com.dsports.returns.domain.model.ReturnRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReturnRepository {

    Mono<ReturnRequest> findById(UUID id);

    Flux<ReturnRequest> findByUserId(UUID userId);

    Flux<ReturnRequest> findAll();

    Mono<ReturnRequest> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    /** Units of the product already requested for return on this order (pending or approved). */
    Mono<Integer> sumHeldQuantity(UUID orderId, UUID productId);

    /** Serialises concurrent return requests for the same order line until the surrounding transaction ends. */
    Mono<Void> lockOrderLine(UUID orderId, UUID productId);

    Mono<Void> save(ReturnRequest request);
}
