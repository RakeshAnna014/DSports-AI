package com.dsports.order.application.order.port;

import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderNumber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface OrderRepository {
    Mono<Order> findById(OrderId id);
    Mono<Order> findByOrderNumber(OrderNumber orderNumber);
    Flux<Order> findByUserId(UUID userId);
    Mono<Boolean> existsByCheckoutId(UUID checkoutId);
    Mono<Long> countOrders();
    Mono<Void> save(Order order);
}
