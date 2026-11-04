package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.AssignCourierCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.CourierGateway.CourierGatewayResult;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
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

class AssignCourierUseCaseTest {

    private ShipmentRepository repository;
    private CourierGateway courierGateway;
    private EventPublisher eventPublisher;
    private AssignCourierUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(ShipmentRepository.class);
        courierGateway = mock(CourierGateway.class);
        eventPublisher = mock(EventPublisher.class);
        useCase = new AssignCourierUseCase(repository, courierGateway, eventPublisher);
    }

    @Test
    void shouldAssignCourierSuccessfully() {
        var shipmentId = UUID.randomUUID();
        var command = new AssignCourierCommand(shipmentId, CourierProvider.MOCK);
        var shipment = Shipment.create(ShipmentId.fromUUID(shipmentId), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));

        var id = ShipmentId.fromUUID(shipmentId);
        when(repository.findById(id)).thenReturn(Mono.just(shipment));
        when(repository.save(any())).thenReturn(Mono.empty());
        when(courierGateway.createShipment(any())).thenReturn(Mono.just(new CourierGatewayResult(true, "Created", "TRK-456", null)));

        StepVerifier.create(useCase.execute(command))
            .assertNext(result -> {
                assertEquals(ShipmentStatus.READY_FOR_PICKUP.name(), result.status());
                assertEquals("TRK-456", result.trackingNumber());
                assertEquals(CourierProvider.MOCK.name(), result.courierProvider());
            })
            .verifyComplete();
    }

    @Test
    void shouldFailWhenShipmentNotFound() {
        var shipmentId = UUID.randomUUID();
        var command = new AssignCourierCommand(shipmentId, CourierProvider.MOCK);

        var id = ShipmentId.fromUUID(shipmentId);
        when(repository.findById(id)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command))
            .expectErrorSatisfies(error -> {
                var ex = (ShipmentDomainException) error;
                assertEquals(ShipmentErrorCode.SHIPMENT_NOT_FOUND, ex.getErrorCode());
            })
            .verify();
    }

    @Test
    void shouldFailWhenShipmentAlreadyAssigned() {
        var shipmentId = UUID.randomUUID();
        var command = new AssignCourierCommand(shipmentId, CourierProvider.MOCK);
        var shipment = Shipment.create(ShipmentId.fromUUID(shipmentId), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());

        var id = ShipmentId.fromUUID(shipmentId);
        when(repository.findById(id)).thenReturn(Mono.just(shipment));

        StepVerifier.create(useCase.execute(command))
            .expectError(ShipmentDomainException.class)
            .verify();
    }
}
