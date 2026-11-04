package com.dsports.config;

import com.dsports.cart.application.port.CartRepository;
import com.dsports.cart.domain.model.CartId;
import com.dsports.cart.domain.model.CartStatus;
import com.dsports.inventory.application.usecase.FulfillOrderReservationsUseCase;
import com.dsports.order.application.checkout.port.CheckoutRepository;
import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.checkout.model.CheckoutId;
import com.dsports.order.domain.order.model.Order;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentCompletionPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CapturePaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.refund.ManageRefundsUseCase;
import com.dsports.payment.application.refund.ProcessRefundUseCase;
import com.dsports.payment.application.refund.RequestRefundUseCase;
import com.dsports.payment.application.refund.port.RefundRepository;
import com.dsports.payment.domain.payment.exception.PaymentDomainException;
import com.dsports.payment.domain.payment.exception.PaymentErrorCode;
import com.dsports.payment.infrastructure.refund.persistence.RefundR2dbcRepositoryAdapter;
import com.dsports.payment.infrastructure.refund.persistence.SpringR2dbcRefundRepository;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shared.application.outbox.OutboxMessage;
import com.dsports.shared.application.outbox.OutboxMessageTypes;
import com.dsports.shared.application.outbox.OutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Configuration
public class PaymentModuleConfig {

    private final Duration gatewayTimeout;

    public PaymentModuleConfig(@Value("${app.payment.gateway-timeout:PT10S}") Duration gatewayTimeout) {
        this.gatewayTimeout = gatewayTimeout;
    }

    @Bean
    public OrderPaymentPort orderPaymentPort(OrderRepository orderRepository) {
        return new OrderPaymentPort() {
            @Override
            public Mono<OrderData> getOrderData(UUID orderId, UUID userId) {
                return orderRepository.findById(OrderId.fromUUID(orderId))
                    .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_NOT_FOUND,
                        "Order not found: " + orderId)))
                    .flatMap(order -> order.getUserId().equals(userId)
                        ? Mono.just(new OrderData(order.getId().value(), order.getUserId(),
                            order.getOrderNumber().value(), order.getGrandTotal(), order.getCurrency(),
                            order.getStatus().name()))
                        : Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_NOT_OWNED_BY_USER,
                            "Order does not belong to the current user")));
            }

            @Override
            public Mono<Void> markOrderRefunded(UUID orderId) {
                return orderRepository.findById(OrderId.fromUUID(orderId))
                    .flatMap(order -> {
                        if (order.getStatus() == OrderStatus.REFUNDED) {
                            return Mono.<Void>empty();
                        }
                        order.markRefunded();
                        order.clearDomainEvents();
                        return orderRepository.save(order);
                    });
            }
        };
    }

    /** Runs inside the capture transaction: every step commits together with the payment's SUCCESS status. */
    @Bean
    public PaymentCompletionPort paymentCompletionPort(OrderRepository orderRepository,
                                                       CheckoutRepository checkoutRepository,
                                                       CartRepository cartRepository,
                                                       FulfillOrderReservationsUseCase fulfillOrderReservationsUseCase,
                                                       OutboxWriter outboxWriter) {
        return orderId -> orderRepository.findById(OrderId.fromUUID(orderId))
            .switchIfEmpty(Mono.error(new PaymentDomainException(PaymentErrorCode.ORDER_NOT_FOUND,
                "Order not found: " + orderId)))
            .flatMap(order -> {
                order.confirm();
                order.clearDomainEvents();
                return orderRepository.save(order)
                    .then(Mono.defer(() -> fulfillOrderReservationsUseCase.execute(orderId)))
                    .then(Mono.defer(() -> completeCart(order, checkoutRepository, cartRepository)))
                    .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.CREATE_SHIPMENT, orderId, Map.of()))))
                    .then(Mono.defer(() -> outboxWriter.enqueue(OutboxMessage.of(OutboxMessageTypes.NOTIFY_ORDER_CONFIRMED, orderId, Map.of(
                        "userId", order.getUserId().toString(),
                        "orderNumber", order.getOrderNumber().value())))));
            });
    }

    private static Mono<Void> completeCart(Order order, CheckoutRepository checkoutRepository, CartRepository cartRepository) {
        return checkoutRepository.findById(CheckoutId.fromUUID(order.getCheckoutId()))
            .flatMap(checkout -> cartRepository.findById(CartId.fromUUID(checkout.getCartId())))
            .filter(cart -> cart.getStatus() != CartStatus.COMPLETED)
            .flatMap(cart -> {
                cart.complete();
                return cartRepository.save(cart);
            });
    }

    @Bean
    public RefundRepository refundRepository(SpringR2dbcRefundRepository springRefundRepository) {
        return new RefundR2dbcRepositoryAdapter(springRefundRepository);
    }

    @Bean
    public CreatePaymentUseCase createPaymentUseCase(PaymentRepository paymentRepository,
                                                      OrderPaymentPort orderPaymentPort,
                                                      PaymentGateway paymentGateway,
                                                      EventPublisher eventPublisher,
                                                      TransactionRunner transactionRunner,
                                                      OutboxWriter outboxWriter) {
        return new CreatePaymentUseCase(paymentRepository, orderPaymentPort, paymentGateway, eventPublisher,
            transactionRunner, outboxWriter, gatewayTimeout);
    }

    @Bean
    public CapturePaymentUseCase capturePaymentUseCase(PaymentRepository paymentRepository,
                                                        PaymentGateway paymentGateway,
                                                        OrderPaymentPort orderPaymentPort,
                                                        PaymentCompletionPort paymentCompletionPort,
                                                        EventPublisher eventPublisher,
                                                        TransactionRunner transactionRunner,
                                                        OutboxWriter outboxWriter) {
        return new CapturePaymentUseCase(paymentRepository, paymentGateway, orderPaymentPort, paymentCompletionPort,
            eventPublisher, transactionRunner, outboxWriter, gatewayTimeout);
    }

    @Bean
    public CancelPaymentUseCase cancelPaymentUseCase(PaymentRepository paymentRepository,
                                                      EventPublisher eventPublisher,
                                                      TransactionRunner transactionRunner,
                                                      OutboxWriter outboxWriter) {
        return new CancelPaymentUseCase(paymentRepository, eventPublisher, transactionRunner, outboxWriter);
    }

    @Bean
    public RequestRefundUseCase requestRefundUseCase(PaymentRepository paymentRepository,
                                                     RefundRepository refundRepository) {
        return new RequestRefundUseCase(paymentRepository, refundRepository);
    }

    @Bean
    public ProcessRefundUseCase processRefundUseCase(RefundRepository refundRepository,
                                                     PaymentRepository paymentRepository,
                                                     PaymentGateway paymentGateway,
                                                     OrderPaymentPort orderPaymentPort,
                                                     EventPublisher eventPublisher,
                                                     TransactionRunner transactionRunner,
                                                     OutboxWriter outboxWriter) {
        return new ProcessRefundUseCase(refundRepository, paymentRepository, paymentGateway, orderPaymentPort,
            eventPublisher, transactionRunner, outboxWriter, gatewayTimeout);
    }

    @Bean
    public ManageRefundsUseCase manageRefundsUseCase(RefundRepository refundRepository,
                                                     PaymentRepository paymentRepository) {
        return new ManageRefundsUseCase(refundRepository, paymentRepository);
    }

    @Bean
    public GetPaymentUseCase getPaymentUseCase(PaymentRepository paymentRepository) {
        return new GetPaymentUseCase(paymentRepository);
    }

    @Bean
    public GetPaymentHistoryUseCase getPaymentHistoryUseCase(PaymentRepository paymentRepository) {
        return new GetPaymentHistoryUseCase(paymentRepository);
    }
}
