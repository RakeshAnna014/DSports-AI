package com.dsports.config;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderItem;
import com.dsports.payment.application.payment.port.PaymentRepository;
import com.dsports.payment.domain.payment.model.PaymentId;
import com.dsports.payment.domain.payment.model.PaymentStatus;
import com.dsports.returns.application.port.ReturnRepository;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.support.application.port.SupportCaseRepository;
import com.dsports.support.application.port.SupportContextPort;
import com.dsports.support.application.usecase.CreateSupportCaseUseCase;
import com.dsports.support.application.usecase.CustomerCaseUseCase;
import com.dsports.support.application.usecase.StaffCaseUseCase;
import com.dsports.support.infrastructure.persistence.SpringR2dbcSupportCaseRepository;
import com.dsports.support.infrastructure.persistence.SupportCaseR2dbcRepositoryAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Wires the support module to the rest of DSports. The support module only knows the {@link SupportContextPort}; this is the
 * single place where it is connected to orders, shipments, payments and returns, and every lookup is owner-scoped.
 */
@Configuration
public class SupportModuleConfig {

    @Bean
    public SupportCaseRepository supportCaseRepository(SpringR2dbcSupportCaseRepository springRepository, DatabaseClient databaseClient) {
        return new SupportCaseR2dbcRepositoryAdapter(springRepository, databaseClient);
    }

    @Bean
    public SupportContextPort supportContextPort(OrderRepository orders, ShipmentRepository shipments, PaymentRepository payments, ReturnRepository returns) {
        return new SupportContextPort() {
            @Override
            public Mono<OrderFacts> orderOfCustomer(java.util.UUID orderId, java.util.UUID userId) {
                return orders.findById(new OrderId(orderId)).filter(order -> order.getUserId().equals(userId)).flatMap(order ->
                    Mono.zip(shipments.findByOrderId(orderId).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
                        payments.findByOrderId(orderId).collectList()).map(t -> {
                            var shipment = t.getT1();
                            var attempts = t.getT2();
                            long successful = attempts.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCESS
                                || p.getStatus() == PaymentStatus.PARTIALLY_REFUNDED || p.getStatus() == PaymentStatus.REFUNDED).count();
                            return new OrderFacts(order.getId().value(), order.getOrderNumber().value(), order.getStatus().name(), order.getGrandTotal(),
                                order.getCurrency(), order.getPlacedAt(), shipment.map(s -> s.getStatus().name()).orElse(null),
                                shipment.map(com.dsports.shipment.domain.model.Shipment::getDeliveredAt).orElse(null), (int) successful, attempts.size(),
                                order.getItems().stream().collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getProductName, (a, b) -> a)));
                        }));
            }

            @Override
            public Mono<Boolean> paymentBelongsTo(java.util.UUID paymentId, java.util.UUID userId) {
                return payments.findById(new PaymentId(paymentId)).map(p -> p.getUserId().equals(userId)).defaultIfEmpty(false);
            }

            @Override
            public Mono<Boolean> returnBelongsTo(java.util.UUID returnId, java.util.UUID userId) {
                return returns.findById(returnId).map(r -> r.getUserId().equals(userId)).defaultIfEmpty(false);
            }
        };
    }

    @Bean
    public CreateSupportCaseUseCase createSupportCaseUseCase(SupportCaseRepository repository, SupportContextPort context, TransactionRunner transactions,
                                                             Clock clock, @Value("${app.support.high-value-threshold:10000}") BigDecimal highValueThreshold) {
        return new CreateSupportCaseUseCase(repository, context, transactions, clock, highValueThreshold);
    }

    @Bean
    public CustomerCaseUseCase customerCaseUseCase(SupportCaseRepository repository, TransactionRunner transactions, Clock clock,
                                                   @Value("${app.support.reopen-window:P7D}") Duration reopenWindow) {
        return new CustomerCaseUseCase(repository, transactions, clock, reopenWindow);
    }

    @Bean
    public StaffCaseUseCase staffCaseUseCase(SupportCaseRepository repository, TransactionRunner transactions, Clock clock) {
        return new StaffCaseUseCase(repository, transactions, clock);
    }
}
