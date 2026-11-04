package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.command.UpdateOrderStatusCommand;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Operator-driven fulfilment progress (PROCESSING, SHIPPED, DELIVERED) and cancellation of unpaid orders.
 * Cancellation is delegated so stock and payments are released exactly as for a customer cancellation.
 */
public class UpdateOrderStatusUseCase {
    private static final Logger log = LoggerFactory.getLogger(UpdateOrderStatusUseCase.class);

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;
    private final CancelOrderUseCase cancelOrderUseCase;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;

    public UpdateOrderStatusUseCase(OrderRepository orderRepository,
                                     EventPublisher eventPublisher,
                                     CancelOrderUseCase cancelOrderUseCase,
                                     TransactionRunner transactionRunner,
                                     OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.cancelOrderUseCase = cancelOrderUseCase;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
    }

    public Mono<OrderResult> execute(UpdateOrderStatusCommand command) {
        if (command.newStatus() == OrderStatus.CANCELLED) {
            return cancelOrderUseCase.executeAsSystem(command.orderId(), "admin");
        }
        return transactionRunner.inTransaction(() -> orderRepository.findById(OrderId.fromUUID(command.orderId()))
                .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_FOUND,
                    "Order not found: " + command.orderId())))
                .flatMap(order -> {
                    order.updateStatus(command.newStatus());
                    return orderRepository.save(order)
                        .then(Mono.defer(() -> enqueueNotification(order)))
                        .thenReturn(order);
                }))
            .map(order -> {
                order.getDomainEvents().forEach(eventPublisher::publish);
                order.clearDomainEvents();
                log.info("Order {} status updated to {}", order.getOrderNumber().value(), command.newStatus());
                return OrderResultMapper.toResult(order);
            });
    }

    private Mono<Void> enqueueNotification(Order order) {
        var type = switch (order.getStatus()) {
            case SHIPPED -> OutboxMessageTypes.NOTIFY_ORDER_SHIPPED;
            case DELIVERED -> OutboxMessageTypes.NOTIFY_ORDER_DELIVERED;
            default -> null;
        };
        if (type == null) {
            return Mono.empty();
        }
        return outboxWriter.enqueue(OutboxMessage.of(type, order.getId().value(), Map.of(
            "userId", order.getUserId().toString(),
            "orderNumber", order.getOrderNumber().value())));
    }
}
