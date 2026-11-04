package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Cancels orders that were never paid within the payment window so their reserved stock becomes sellable again.
 * Each order is cancelled in its own transaction; one failure (for example a capture in progress) does not stop
 * the others and is retried on the next run.
 */
public class ExpireUnpaidOrdersUseCase {
    private static final Logger log = LoggerFactory.getLogger(ExpireUnpaidOrdersUseCase.class);

    private final OrderRepository orderRepository;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final Duration paymentWindow;

    public ExpireUnpaidOrdersUseCase(OrderRepository orderRepository, CancelOrderUseCase cancelOrderUseCase,
                                     Duration paymentWindow) {
        this.orderRepository = orderRepository;
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.paymentWindow = paymentWindow;
    }

    /** @return number of orders cancelled in this run */
    public Mono<Long> execute() {
        var cutoff = Instant.now().minus(paymentWindow);
        return orderRepository.findByStatusPlacedBefore(OrderStatus.PENDING_PAYMENT, cutoff)
            .concatMap(order -> cancelOrderUseCase.executeAsSystem(order.getId().value(), "payment window expired")
                .thenReturn(1L)
                .onErrorResume(e -> {
                    log.warn("Could not expire unpaid order {}: {}", order.getOrderNumber().value(), e.getMessage());
                    return Mono.just(0L);
                }))
            .reduce(0L, Long::sum);
    }
}
