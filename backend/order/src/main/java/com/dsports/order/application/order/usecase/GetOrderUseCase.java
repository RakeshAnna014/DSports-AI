package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.query.GetOrderQuery;
import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.OrderId;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class GetOrderUseCase {
    private final OrderRepository orderRepository;

    public GetOrderUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Mono<OrderResult> execute(GetOrderQuery query) {
        return orderRepository.findById(OrderId.fromUUID(query.orderId()))
            .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + query.orderId())))
            .flatMap(order -> {
                if (!order.getUserId().equals(query.userId())) {
                    return Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_OWNED_BY_USER,
                        "Order does not belong to user: " + query.userId()));
                }
                return Mono.just(OrderResultMapper.toResult(order));
            });
    }

    public Mono<OrderResult> getById(UUID orderId) {
        return orderRepository.findById(OrderId.fromUUID(orderId))
            .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + orderId)))
            .map(OrderResultMapper::toResult);
    }
}
