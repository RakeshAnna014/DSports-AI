package com.dsports.returns.application.usecase;

import com.dsports.returns.application.port.ReturnOrderPort;
import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.returns.domain.policy.ReturnEligibility;
import com.dsports.returns.domain.policy.ReturnPolicy;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.UUID;

/**
 * Answers "can this customer return this product from this order, and how many units?". A missing order and another
 * customer's order are indistinguishable (both ORDER_NOT_FOUND), so order IDs cannot be probed.
 */
public class CheckReturnEligibilityUseCase {

    private final ReturnOrderPort orderPort;
    private final ReturnRepository returnRepository;
    private final ReturnPolicy policy;
    private final Clock clock;

    public CheckReturnEligibilityUseCase(ReturnOrderPort orderPort, ReturnRepository returnRepository,
                                         ReturnPolicy policy, Clock clock) {
        this.orderPort = orderPort;
        this.returnRepository = returnRepository;
        this.policy = policy;
        this.clock = clock;
    }

    public Mono<ReturnEligibility> execute(UUID orderId, UUID productId, UUID userId) {
        return orderPort.findOrderOfCustomer(orderId, userId)
            .flatMap(order -> returnRepository.sumHeldQuantity(orderId, productId)
                .map(held -> policy.evaluate(orderId, productId, order, held, clock.instant())))
            .switchIfEmpty(Mono.fromSupplier(() -> policy.evaluate(orderId, productId, null, 0, clock.instant())));
    }
}
