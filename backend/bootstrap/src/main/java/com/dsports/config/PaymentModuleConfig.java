package com.dsports.config;

import com.dsports.payment.application.payment.port.EventPublisher;
import com.dsports.payment.application.payment.port.OrderPaymentPort;
import com.dsports.payment.application.payment.port.PaymentGateway;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.application.payment.usecase.CancelPaymentUseCase;
import com.dsports.payment.application.payment.usecase.CreatePaymentUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentHistoryUseCase;
import com.dsports.payment.application.payment.usecase.GetPaymentUseCase;
import com.dsports.payment.application.payment.usecase.RefundPaymentUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Configuration
public class PaymentModuleConfig {

    @Bean
    public OrderPaymentPort orderPaymentPort(
            com.dsports.order.application.order.port.OrderRepository orderRepository) {
        return new OrderPaymentPort() {
            @Override
            public Mono<OrderData> getOrderData(UUID orderId, UUID userId) {
                return orderRepository.findById(com.dsports.order.domain.order.model.OrderId.fromUUID(orderId))
                    .map(order -> {
                        if (!order.getUserId().equals(userId)) {
                            throw new com.dsports.payment.domain.payment.exception.PaymentDomainException(
                                com.dsports.payment.domain.payment.exception.PaymentErrorCode.ORDER_NOT_OWNED_BY_USER,
                                "Order does not belong to user");
                        }
                        var alreadyPaid = order.getStatus() == com.dsports.order.domain.order.model.OrderStatus.CONFIRMED
                            || order.getStatus() == com.dsports.order.domain.order.model.OrderStatus.PROCESSING
                            || order.getStatus() == com.dsports.order.domain.order.model.OrderStatus.SHIPPED
                            || order.getStatus() == com.dsports.order.domain.order.model.OrderStatus.DELIVERED;
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
                return Mono.empty();
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
