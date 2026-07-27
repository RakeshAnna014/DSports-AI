package com.dsports.config;

import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CapturePaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.payment.usecase.RefundPaymentUseCase;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Configuration
public class PaymentModuleConfig {
    private static final Logger log = LoggerFactory.getLogger(PaymentModuleConfig.class);

    @Bean
    public OrderPaymentPort orderPaymentPort(
            com.dsports.order.application.order.port.OrderRepository orderRepository) {
        return new OrderPaymentPort() {
            @Override
            public Mono<OrderData> getOrderData(UUID orderId, UUID userId) {
                return orderRepository.findById(OrderId.fromUUID(orderId))
                    .map(order -> {
                        if (!order.getUserId().equals(userId)) {
                            throw new com.dsports.payment.domain.payment.exception.PaymentDomainException(
                                com.dsports.payment.domain.payment.exception.PaymentErrorCode.ORDER_NOT_OWNED_BY_USER,
                                "Order does not belong to user");
                        }
                        var alreadyPaid = order.getStatus() == OrderStatus.CONFIRMED
                            || order.getStatus() == OrderStatus.PROCESSING
                            || order.getStatus() == OrderStatus.SHIPPED
                            || order.getStatus() == OrderStatus.DELIVERED;
                        return new OrderData(
                            order.getId().value(),
                            order.getUserId(),
                            order.getGrandTotal(),
                            order.getCurrency(),
                            alreadyPaid
                        );
                    })
                    .switchIfEmpty(Mono.error(
                        new com.dsports.payment.domain.payment.exception.PaymentDomainException(
                            com.dsports.payment.domain.payment.exception.PaymentErrorCode.ORDER_NOT_FOUND,
                            "Order not found: " + orderId)));
            }

            @Override
            public Mono<Void> markOrderAsPaid(UUID orderId) {
                return orderRepository.findById(OrderId.fromUUID(orderId))
                    .flatMap(order -> {
                        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
                            order.confirm();
                            log.info("Order {} confirmed after payment", order.getOrderNumber());
                        } else {
                            log.warn("Order {} is in status {} (expected PENDING_PAYMENT), skipping confirmation",
                                order.getOrderNumber(), order.getStatus());
                        }
                        return orderRepository.save(order);
                    })
                    .doOnError(e -> log.error("Failed to mark order {} as paid: {}", orderId, e.getMessage()))
                    .onErrorResume(e -> Mono.empty());
            }
        };
    }

    @Bean
    public CreatePaymentUseCase createPaymentUseCase(PaymentRepository paymentRepository,
                                                      OrderPaymentPort orderPaymentPort,
                                                      PaymentGateway paymentGateway,
                                                      EventPublisher eventPublisher) {
        return new CreatePaymentUseCase(paymentRepository, orderPaymentPort, paymentGateway, eventPublisher);
    }

    @Bean
    public CapturePaymentUseCase capturePaymentUseCase(PaymentRepository paymentRepository,
                                                        PaymentGateway paymentGateway,
                                                        EventPublisher eventPublisher) {
        return new CapturePaymentUseCase(paymentRepository, paymentGateway, eventPublisher);
    }

    @Bean
    public CancelPaymentUseCase cancelPaymentUseCase(PaymentRepository paymentRepository,
                                                      PaymentGateway paymentGateway,
                                                      EventPublisher eventPublisher) {
        return new CancelPaymentUseCase(paymentRepository, paymentGateway, eventPublisher);
    }

    @Bean
    public RefundPaymentUseCase refundPaymentUseCase(PaymentRepository paymentRepository,
                                                      PaymentGateway paymentGateway,
                                                      OrderPaymentPort orderPaymentPort,
                                                      EventPublisher eventPublisher) {
        return new RefundPaymentUseCase(paymentRepository, paymentGateway, orderPaymentPort, eventPublisher);
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
