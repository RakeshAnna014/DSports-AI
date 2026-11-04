package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.UpdateShipmentStatusCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shared.application.TransactionRunner;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.OrderFulfilmentPort;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.ShippingAddress;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateShipmentStatusUseCaseTest {

    private ShipmentRepository repository;
    private CourierGateway courierGateway;
    private EventPublisher eventPublisher;
    private OrderFulfilmentPort fulfilmentPort;
    private UpdateShipmentStatusUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(ShipmentRepository.class);
        courierGateway = mock(CourierGateway.class);
        eventPublisher = mock(EventPublisher.class);
        fulfilmentPort = mock(OrderFulfilmentPort.class);
        when(fulfilmentPort.shipmentProgressed(any(), any())).thenReturn(Mono.empty());
        TransactionRunner passThrough = new TransactionRunner() {
            @Override
            public <T> Mono<T> inTransaction(java.util.function.Supplier<Mono<T>> work) {
                return Mono.defer(work);
            }
        };
        useCase = new UpdateShipmentStatusUseCase(repository, courierGateway, eventPublisher, fulfilmentPort, passThrough);
    }

    @Test
    void shouldUpdateToDelivered() {
        var shipmentId = UUID.randomUUID();
        var command = new UpdateShipmentStatusCommand(shipmentId, ShipmentStatus.DELIVERED, null);
        var shipment = Shipment.create(ShipmentId.fromUUID(shipmentId), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());
        shipment.pickup();
        shipment.transit();
        shipment.outForDelivery();

        var id = ShipmentId.fromUUID(shipmentId);
        when(repository.findById(id)).thenReturn(Mono.just(shipment));
        when(repository.save(any())).thenReturn(Mono.empty());
        when(repository.saveTracking(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertEquals(ShipmentStatus.DELIVERED.name(), result.status());
                assertEquals(shipmentId, result.id());
            })
            .verifyComplete();
    }

    @Test
    void shouldFailOnInvalidTransition() {
        var shipmentId = UUID.randomUUID();
        var command = new UpdateShipmentStatusCommand(shipmentId, ShipmentStatus.DELIVERED, null);
        var shipment = Shipment.create(ShipmentId.fromUUID(shipmentId), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));

        var id = ShipmentId.fromUUID(shipmentId);
        when(repository.findById(id)).thenReturn(Mono.just(shipment));

        StepVerifier.create(useCase.execute(command))
            .expectError(ShipmentDomainException.class)
            .verify();
    }
}
