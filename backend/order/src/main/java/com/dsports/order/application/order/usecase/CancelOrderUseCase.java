package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.command.CancelOrderCommand;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.InventoryReservationPort;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.port.PaymentVoidPort;
import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Cancels an unpaid order in one transaction: the order becomes CANCELLED, its reserved stock is released,
 * open payment attempts are voided and a cancellation notification is recorded. Paid orders are rejected by the
 * domain and must be refunded instead.
 */
public class CancelOrderUseCase {
    private static final Logger log = LoggerFactory.getLogger(CancelOrderUseCase.class);

    private final OrderRepository orderRepository;
    private final EventPublisher eventPublisher;
    private final InventoryReservationPort inventoryReservationPort;
    private final PaymentVoidPort paymentVoidPort;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;

    public CancelOrderUseCase(OrderRepository orderRepository,
                               EventPublisher eventPublisher,
                               InventoryReservationPort inventoryReservationPort,
                               PaymentVoidPort paymentVoidPort,
                               TransactionRunner transactionRunner,
                               OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.inventoryReservationPort = inventoryReservationPort;
        this.paymentVoidPort = paymentVoidPort;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
    }

    /** Customer cancellation: the order must belong to the caller. */
    public Mono<OrderResult> execute(CancelOrderCommand command) {
        return cancel(command.orderId(), command.userId(), "customer");
    }

    /** Operator or system cancellation (admin action, unpaid-order expiry). */
    public Mono<OrderResult> executeAsSystem(UUID orderId, String reason) {
        return cancel(orderId, null, reason);
    }

    private Mono<OrderResult> cancel(UUID orderId, UUID requiredOwner, String reason) {
        return transactionRunner.inTransaction(() -> orderRepository.findById(OrderId.fromUUID(orderId))
                .switchIfEmpty(Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_FOUND,
                    "Order not found: " + orderId)))
                .flatMap(order -> {
                    if (requiredOwner != null && !order.getUserId().equals(requiredOwner)) {
                        return Mono.error(new OrderDomainException(OrderErrorCode.ORDER_NOT_OWNED_BY_USER,
                            "Order does not belong to the current user"));
                    }
                    order.cancel();
                    return orderRepository.save(order)
                        .then(Mono.defer(() -> inventoryReservationPort.release(orderId)))
                        .then(Mono.defer(() -> paymentVoidPort.voidOpenPayments(orderId)))
                        .then(Mono.defer(() -> outboxWriter.enqueue(cancelledNotification(order))))
                        .thenReturn(order);
                }))
            .map(order -> {
                order.getDomainEvents().forEach(eventPublisher::publish);
                order.clearDomainEvents();
                log.info("Order {} cancelled ({})", order.getOrderNumber().value(), reason);
                return OrderResultMapper.toResult(order);
            });
    }

    private static OutboxMessage cancelledNotification(Order order) {
        return OutboxMessage.of(OutboxMessageTypes.NOTIFY_ORDER_CANCELLED, order.getId().value(), Map.of(
            "userId", order.getUserId().toString(),
            "orderNumber", order.getOrderNumber().value()));
    }
}
