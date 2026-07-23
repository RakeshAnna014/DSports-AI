package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.command.CancelOrderCommand;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.OrderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class CancelOrderUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelOrderUseCase.class);

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;

    public CancelOrderUseCase(OrderRepository orderRepository, EventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    public Mono<OrderResult> execute(CancelOrderCommand command) {
        return orderRepository.findById(OrderId.fromUUID(command.orderId()))
            .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + command.orderId())))
            .flatMap(order -> {
                if (!order.getUserId().equals(command.userId())) {
                    return Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_OWNED_BY_USER,
                        "Order does not belong to user: " + command.userId()));
                }
                order.cancel();
                return orderRepository.save(order)
                    .thenReturn(order);
            })
            .map(order -> {
                order.getDomainEvents().forEach(eventPublisher::publish);
                order.clearDomainEvents();
                log.info("Order cancelled: {} by user {}", order.getOrderNumber(), command.userId());
                return OrderResultMapper.toResult(order);
            });
    }
}
