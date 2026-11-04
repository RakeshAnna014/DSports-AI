package com.dsports.config;

import com.dsports.order.application.order.command.UpdateOrderStatusCommand;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.usecase.UpdateOrderStatusUseCase;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.shipment.application.port.OrderFulfilmentPort;
import com.dsports.shipment.domain.model.ShipmentStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
public class ShipmentModuleConfig {

    private static final List<OrderStatus> FULFILMENT_PATH =
        List.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED);

    /**
     * Moves the order forward along CONFIRMED → PROCESSING → SHIPPED → DELIVERED until it matches the shipment.
     * Each step goes through the order's own state machine (and its notifications); it never moves an order back.
     */
    @Bean
    public OrderFulfilmentPort orderFulfilmentPort(OrderRepository orderRepository,
                                                   UpdateOrderStatusUseCase updateOrderStatusUseCase) {
        return (orderId, shipmentStatus) -> {
            var target = switch (shipmentStatus) {
                case PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY -> OrderStatus.SHIPPED;
                case DELIVERED -> OrderStatus.DELIVERED;
                default -> null;
            };
            if (target == null) {
                return Mono.empty();
            }
            return orderRepository.findById(OrderId.fromUUID(orderId))
                .flatMapMany(order -> {
                    var current = FULFILMENT_PATH.indexOf(order.getStatus());
                    var targetIndex = FULFILMENT_PATH.indexOf(target);
                    var inFulfilment = order.getStatus() == OrderStatus.CONFIRMED || current >= 0;
                    if (!inFulfilment || current >= targetIndex) {
                        return Flux.<OrderStatus>empty();
                    }
                    return Flux.fromIterable(FULFILMENT_PATH.subList(current + 1, targetIndex + 1));
                })
                .concatMap(step -> updateOrderStatusUseCase.execute(new UpdateOrderStatusCommand(orderId, step)))
                .then();
        };
    }
}
