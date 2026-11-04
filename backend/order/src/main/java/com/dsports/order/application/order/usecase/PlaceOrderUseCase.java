package com.dsports.order.application.order.usecase;

import com.dsports.order.application.order.command.PlaceOrderCommand;
import com.dsports.order.application.order.port.CartCheckoutPort;
import com.dsports.order.application.order.port.CheckoutDataPort;
import com.dsports.order.application.order.port.EventPublisher;
import com.dsports.order.application.order.port.InventoryReservationPort;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.application.order.result.OrderResult;
import com.dsports.order.application.order.result.OrderResultMapper;
import com.dsports.order.domain.order.exception.OrderDomainException;
import com.dsports.order.domain.order.exception.OrderErrorCode;
import com.dsports.order.domain.order.model.AddressSnapshot;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.order.domain.order.model.OrderItemId;
import com.dsports.order.domain.order.model.OrderNumber;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Places an order from a validated checkout as one local transaction: order and items, stock reservations,
 * cart checkout, checkout completion and the order-placed notification record either all commit or none do.
 * Domain events are published only after the commit.
 */
public class PlaceOrderUseCase {
    private static final Logger log = LoggerFactory.getLogger(PlaceOrderUseCase.class);

    private final OrderRepository orderRepository;
    private final CheckoutDataPort checkoutDataPort;
    private final CartCheckoutPort cartCheckoutPort;
    private final InventoryReservationPort inventoryReservationPort;
    private final EventPublisher eventPublisher;
    private final TransactionRunner transactionRunner;
    private final OutboxWriter outboxWriter;

    public PlaceOrderUseCase(OrderRepository orderRepository,
                              CheckoutDataPort checkoutDataPort,
                              CartCheckoutPort cartCheckoutPort,
                              InventoryReservationPort inventoryReservationPort,
                              EventPublisher eventPublisher,
                              TransactionRunner transactionRunner,
                              OutboxWriter outboxWriter) {
        this.orderRepository = orderRepository;
        this.checkoutDataPort = checkoutDataPort;
        this.cartCheckoutPort = cartCheckoutPort;
        this.inventoryReservationPort = inventoryReservationPort;
        this.eventPublisher = eventPublisher;
        this.transactionRunner = transactionRunner;
        this.outboxWriter = outboxWriter;
    }

    public Mono<OrderResult> execute(PlaceOrderCommand command) {
        return transactionRunner.inTransaction(() -> placeOrder(command))
            .map(order -> {
                order.getDomainEvents().forEach(eventPublisher::publish);
                order.clearDomainEvents();
                log.info("Order placed: {} for user {} total {} {}", order.getOrderNumber().value(),
                    order.getUserId(), order.getGrandTotal(), order.getCurrency());
                return OrderResultMapper.toResult(order);
            });
    }

    private Mono<Order> placeOrder(PlaceOrderCommand command) {
        return orderRepository.existsByCheckoutId(command.checkoutId())
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new OrderDomainException(OrderErrorCode.DUPLICATE_ORDER,
                        "An order for checkout " + command.checkoutId() + " already exists"));
                }
                return checkoutDataPort.getValidatedCheckout(command.checkoutId(), command.userId());
            })
            .flatMap(checkoutData -> orderRepository.nextOrderSequence()
                .map(sequence -> buildOrder(command, checkoutData, sequence))
                .flatMap(order -> {
                    order.markPendingPayment();
                    var reservationItems = order.getItems().stream()
                        .map(item -> new InventoryReservationPort.ReservationItem(
                            item.getProductId(), item.getQuantity()))
                        .toList();
                    return orderRepository.save(order)
                        .then(Mono.defer(() -> inventoryReservationPort.reserve(order.getId().value(), reservationItems)))
                        .then(Mono.defer(() -> cartCheckoutPort.markCartAsCheckedOut(checkoutData.cartId())))
                        .then(Mono.defer(() -> checkoutDataPort.completeCheckout(command.checkoutId())))
                        .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_ORDER_PLACED,
                            order.getId().value(), Map.of(
                                "userId", order.getUserId().toString(),
                                "orderNumber", order.getOrderNumber().value(),
                                "grandTotal", order.getGrandTotal().toPlainString(),
                                "currency", order.getCurrency())))))
                        .thenReturn(order);
                }));
    }

    private Order buildOrder(PlaceOrderCommand command, CheckoutDataPort.CheckoutData checkoutData, long sequence) {
        var orderId = OrderId.generate();
        var orderNumber = OrderNumber.generate(sequence);
        var shippingAddress = new AddressSnapshot(
            checkoutData.shippingAddress().line1(),
            checkoutData.shippingAddress().line2(),
            checkoutData.shippingAddress().city(),
            checkoutData.shippingAddress().state(),
            checkoutData.shippingAddress().country(),
            checkoutData.shippingAddress().postalCode(),
            checkoutData.shippingAddress().fullName(),
            checkoutData.shippingAddress().phone()
        );
        var billingAddress = checkoutData.billingAddress() != null
            ? new AddressSnapshot(
                checkoutData.billingAddress().line1(),
                checkoutData.billingAddress().line2(),
                checkoutData.billingAddress().city(),
                checkoutData.billingAddress().state(),
                checkoutData.billingAddress().country(),
                checkoutData.billingAddress().postalCode(),
                checkoutData.billingAddress().fullName(),
                checkoutData.billingAddress().phone()
              )
            : shippingAddress;

        var items = checkoutData.items().stream()
            .map(item -> OrderItem.create(
                OrderItemId.generate(),
                orderId,
                item.productId(),
                item.productName(),
                item.sku(),
                item.quantity(),
                item.unitPrice(),
                item.lineTotal(),
                item.productImage()
            ))
            .toList();

        var order = Order.place(
            orderId, orderNumber, command.userId(), command.checkoutId(),
            shippingAddress, billingAddress, items,
            checkoutData.subtotal(), checkoutData.shippingCharge(),
            checkoutData.taxAmount(), checkoutData.discountAmount(),
            checkoutData.totalAmount(), checkoutData.currency()
        );
        order.attachPromotion(checkoutData.promotionCode());
        return order;
    }
}
