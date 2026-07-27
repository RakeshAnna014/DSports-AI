package com.dsports.event;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.domain.model.CartId;
import com.dsports.inventory.application.port.InventoryRepository;
import com.dsports.inventory.domain.model.Quantity;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.domain.payment.event.PaymentSucceededEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class PaymentSucceededEventHandler {
    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededEventHandler.class);

    private final OrderPaymentPort orderPaymentPort;
    private final com.dsports.order.application.order.port.OrderRepository orderRepository;
    private final CheckoutRepository checkoutRepository;
    private final CartRepository cartRepository;
    private final InventoryRepository inventoryRepository;
    private final TransactionalOperator transactionalOperator;

    public PaymentSucceededEventHandler(OrderPaymentPort orderPaymentPort,
                                         com.dsports.order.application.order.port.OrderRepository orderRepository,
                                         CheckoutRepository checkoutRepository,
                                         CartRepository cartRepository,
                                         InventoryRepository inventoryRepository,
                                         ReactiveTransactionManager transactionManager) {
        this.orderPaymentPort = orderPaymentPort;
        this.orderRepository = orderRepository;
        this.checkoutRepository = checkoutRepository;
        this.cartRepository = cartRepository;
        this.inventoryRepository = inventoryRepository;
        this.transactionalOperator = TransactionalOperator.create(transactionManager);
    }

    @EventListener
    public void handlePaymentSucceeded(PaymentSucceededEvent event) {
        log.info("Payment succeeded event received for order {} with payment {}",
            event.getOrderId(), event.getPaymentReference().value());

        processPostPayment(event.getOrderId())
            .as(transactionalOperator::transactional)
            .doOnSuccess(v -> log.info("Post-payment processing completed for order {}", event.getOrderId()))
            .doOnError(e -> log.error("Failed to process payment success for order {}: {}",
                event.getOrderId(), e.getMessage()))
            .subscribe();
    }

    private Mono<Void> processPostPayment(UUID orderId) {
        return orderPaymentPort.markOrderAsPaid(orderId)
            .then(deductInventory(orderId))
            .then(completeCart(orderId));
    }

    private Mono<Void> deductInventory(UUID orderId) {
        return orderRepository.findById(OrderId.fromUUID(orderId))
            .flatMapMany(order -> Flux.fromIterable(order.getItems()))
            .flatMap(item -> deductForItem(item.getProductId(), item.getQuantity()))
            .then();
    }

    private Mono<Void> deductForItem(UUID productId, int quantity) {
        return inventoryRepository.findByProductId(
                com.dsports.inventory.domain.model.ProductId.fromUUID(productId))
            .next()
            .flatMap(inv -> {
                inv.stockOut(Quantity.from(quantity));
                return inventoryRepository.save(inv);
            })
            .doOnSuccess(v -> log.debug("Inventory deducted for product {} (qty: {})", productId, quantity))
            .onErrorResume(e -> {
                log.error("Failed to deduct inventory for product {}: {}", productId, e.getMessage());
                return Mono.empty();
            });
    }

    private Mono<Void> completeCart(UUID orderId) {
        return orderRepository.findById(OrderId.fromUUID(orderId))
            .flatMap(order -> checkoutRepository.findById(CheckoutId.fromUUID(order.getCheckoutId())))
            .flatMap(checkout -> cartRepository.findById(CartId.fromUUID(checkout.getCartId())))
            .flatMap(cart -> {
                cart.complete();
                return cartRepository.save(cart);
            })
            .doOnSuccess(v -> log.debug("Cart completed after successful payment for order {}", orderId))
            .onErrorResume(e -> {
                log.error("Failed to complete cart for order {}: {}", orderId, e.getMessage());
                return Mono.empty();
            });
    }
}
