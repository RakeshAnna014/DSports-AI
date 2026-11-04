package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.application.query.TrackShipmentQuery;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentNumber;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.ShippingAddress;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackShipmentUseCaseTest {

    private ShipmentRepository repository;
    private TrackShipmentUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(ShipmentRepository.class);
        useCase = new TrackShipmentUseCase(repository);
    }

    @Test
    void shouldTrackByTrackingNumber() {
        var trackingNumber = new TrackingNumber("TRK-001");
        var orderId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var shipmentId = ShipmentId.generate();
        var address = new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India");
        var shipment = Shipment.reconstitute(
            shipmentId, ShipmentNumber.generate(), trackingNumber,
            orderId, userId, CourierProvider.MOCK, ShipmentStatus.IN_TRANSIT,
            address, null, null, Instant.now(), null, Instant.now(), Instant.now(), 0
        );

        when(repository.findByTrackingNumber(trackingNumber)).thenReturn(Mono.just(shipment));
        var query = new TrackShipmentQuery(trackingNumber.value(), userId, false);

        StepVerifier.create(useCase.execute(query))
            .assertNext(result -> {
                assertEquals(shipmentId.value(), result.id());
                assertEquals(trackingNumber.value(), result.trackingNumber());
                assertEquals(ShipmentStatus.IN_TRANSIT.name(), result.status());
            })
            .verifyComplete();
    }

    @Test
    void shouldFailWhenTrackingNumberNotFound() {
        var trackingNumber = new TrackingNumber("TRK-UNKNOWN");
        when(repository.findByTrackingNumber(trackingNumber)).thenReturn(Mono.empty());
        var query = new TrackShipmentQuery(trackingNumber.value(), UUID.randomUUID(), false);

        StepVerifier.create(useCase.execute(query))
            .expectError(ShipmentDomainException.class)
            .verify();
    }

    @Test
    void shouldNotRevealAnotherCustomersShipment() {
        var trackingNumber = new TrackingNumber("TRK-OTHER");
        var address = new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India");
        var shipment = Shipment.reconstitute(
            ShipmentId.generate(), ShipmentNumber.generate(), trackingNumber,
            UUID.randomUUID(), UUID.randomUUID(), CourierProvider.MOCK, ShipmentStatus.IN_TRANSIT,
            address, null, null, Instant.now(), null, Instant.now(), Instant.now(), 0
        );
        when(repository.findByTrackingNumber(trackingNumber)).thenReturn(Mono.just(shipment));

        StepVerifier.create(useCase.execute(new TrackShipmentQuery(trackingNumber.value(), UUID.randomUUID(), false)))
            .expectError(ShipmentDomainException.class)
            .verify();
        StepVerifier.create(useCase.execute(new TrackShipmentQuery(trackingNumber.value(), UUID.randomUUID(), true)))
            .expectNextCount(1)
            .verifyComplete();
    }
}
