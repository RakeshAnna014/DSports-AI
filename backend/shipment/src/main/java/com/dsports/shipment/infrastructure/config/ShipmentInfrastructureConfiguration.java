package com.dsports.shipment.infrastructure.config;

import com.dsports.order.application.order.port.OrderRepository;
import com.dsports.order.domain.order.model.OrderId;
import com.dsports.order.domain.order.model.OrderStatus;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shipment.application.port.OrderFulfilmentPort;
import com.dsports.shipment.application.port.OrderQueryService;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.usecase.AssignCourierUseCase;
import com.dsports.shipment.application.usecase.CreateShipmentUseCase;
import com.dsports.shipment.application.usecase.GetShipmentByOrderUseCase;
import com.dsports.shipment.application.usecase.GetShipmentUseCase;
import com.dsports.shipment.application.usecase.TrackShipmentUseCase;
import com.dsports.shipment.application.usecase.UpdateShipmentStatusUseCase;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.infrastructure.event.ShipmentSpringEventPublisherAdapter;
import com.dsports.shipment.infrastructure.gateway.MockCourierGateway;
import com.dsports.shipment.infrastructure.persistence.repository.ShipmentR2dbcRepositoryAdapter;
import com.dsports.shipment.infrastructure.persistence.repository.SpringR2dbcShipmentRepository;
import com.dsports.shipment.infrastructure.persistence.repository.SpringR2dbcShipmentTrackingRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class ShipmentInfrastructureConfiguration {

    @Bean
    public EventPublisher shipmentEventPublisher(ApplicationEventPublisher springPublisher) {
        return new ShipmentSpringEventPublisherAdapter(springPublisher);
    }

    @Bean
    public CourierGateway courierGateway() {
        return new MockCourierGateway();
    }

    @Bean
    public ShipmentRepository shipmentRepository(SpringR2dbcShipmentRepository springRepo,
                                                  SpringR2dbcShipmentTrackingRepository trackingRepo,
                                                  EventPublisher eventPublisher,
                                                  ReactiveTransactionManager transactionManager) {
        return new ShipmentR2dbcRepositoryAdapter(springRepo, trackingRepo, eventPublisher, transactionManager);
    }

    @Bean
    public OrderQueryService shipmentOrderQueryService(OrderRepository orderRepository) {
        return orderId -> orderRepository.findById(OrderId.fromUUID(orderId))
                .map(order -> {
                    var address = order.getShippingAddressSnapshot();
                    var street = address.line2() == null || address.line2().isBlank()
                            ? address.line1()
                            : address.line1() + ", " + address.line2();
                    var paid = order.getStatus() == OrderStatus.CONFIRMED
                            || order.getStatus() == OrderStatus.PROCESSING
                            || order.getStatus() == OrderStatus.SHIPPED;
                    return new OrderQueryService.OrderForShipment(order.getId().value(), order.getUserId(), paid,
                            street, address.city(), address.state(), address.postalCode(), address.country());
                });
    }

    @Bean
    public CreateShipmentUseCase createShipmentUseCase(ShipmentRepository shipmentRepository,
                                                        OrderQueryService orderQueryService,
                                                        CourierGateway courierGateway,
                                                        EventPublisher eventPublisher) {
        return new CreateShipmentUseCase(shipmentRepository, orderQueryService, courierGateway, eventPublisher);
    }

    @Bean
    public AssignCourierUseCase assignCourierUseCase(ShipmentRepository shipmentRepository,
                                                      CourierGateway courierGateway,
                                                      EventPublisher eventPublisher) {
        return new AssignCourierUseCase(shipmentRepository, courierGateway, eventPublisher);
    }

    @Bean
    public TrackShipmentUseCase trackShipmentUseCase(ShipmentRepository shipmentRepository) {
        return new TrackShipmentUseCase(shipmentRepository);
    }

    @Bean
    public GetShipmentByOrderUseCase getShipmentByOrderUseCase(ShipmentRepository shipmentRepository) {
        return new GetShipmentByOrderUseCase(shipmentRepository);
    }

    @Bean
    public GetShipmentUseCase getShipmentUseCase(ShipmentRepository shipmentRepository) {
        return new GetShipmentUseCase(shipmentRepository);
    }

    @Bean
    public UpdateShipmentStatusUseCase updateShipmentStatusUseCase(ShipmentRepository shipmentRepository,
                                                                    CourierGateway courierGateway,
                                                                    EventPublisher eventPublisher,
                                                                    OrderFulfilmentPort orderFulfilmentPort,
                                                                    TransactionRunner transactionRunner) {
        return new UpdateShipmentStatusUseCase(shipmentRepository, courierGateway, eventPublisher,
                orderFulfilmentPort, transactionRunner);
    }
}
