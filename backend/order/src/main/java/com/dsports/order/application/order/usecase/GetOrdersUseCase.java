package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.query.GetOrdersQuery;
import com.dsports.order.application.order.result.OrderSummaryResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import reactor.core.publisher.Flux;

public class GetOrdersUseCase {
    private final OrderRepository orderRepository;

    public GetOrdersUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Flux<OrderSummaryResult> execute(GetOrdersQuery query) {
        return orderRepository.findByUserId(query.userId())
            .map(OrderResultMapper::toSummary);
    }
}
