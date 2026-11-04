package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.domain.model.ShipmentTracking;
import com.dsports.shipment.domain.model.ShippingAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetShipmentByOrderUseCaseTest {

    private final ShipmentRepository repository = mock(ShipmentRepository.class);
    private GetShipmentByOrderUseCase useCase;
    private final UUID orderId = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private Shipment shipment;

    @BeforeEach
    void setUp() {
        useCase = new GetShipmentByOrderUseCase(repository);
        shipment = Shipment.create(ShipmentId.generate(), orderId, owner,
            new ShippingAddress("1 MG Road", "Bengaluru", "KA", "560001", "IN"));
        when(repository.findByOrderId(orderId)).thenReturn(Mono.just(shipment));
        when(repository.findTrackingByShipmentId(shipment.getId())).thenReturn(Flux.just(
            new ShipmentTracking(UUID.randomUUID(), shipment.getId(), ShipmentStatus.CREATED, "Warehouse", "Created", Instant.now())));
    }

    @Test
    void ownerSeesShipmentWithTrackingHistory() {
        StepVerifier.create(useCase.execute(orderId, owner, false))
            .assertNext(result -> {
                assertThat(result.shipment().orderId()).isEqualTo(orderId);
                assertThat(result.events()).singleElement().satisfies(e -> assertThat(e.status()).isEqualTo("CREATED"));
            })
            .verifyComplete();
    }

    @Test
    void anotherCustomerIsRejected() {
        StepVerifier.create(useCase.execute(orderId, UUID.randomUUID(), false))
            .expectErrorMatches(e -> e instanceof ShipmentDomainException sde
                && sde.getErrorCode() == ShipmentErrorCode.SHIPMENT_NOT_OWNED_BY_USER)
            .verify();
    }

    @Test
    void adminMaySeeAnyShipment() {
        StepVerifier.create(useCase.execute(orderId, UUID.randomUUID(), true)).expectNextCount(1).verifyComplete();
    }

    @Test
    void orderWithoutShipmentYetIsNotFound() {
        var other = UUID.randomUUID();
        when(repository.findByOrderId(other)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(other, owner, false))
            .expectErrorMatches(e -> e instanceof ShipmentDomainException sde
                && sde.getErrorCode() == ShipmentErrorCode.SHIPMENT_NOT_FOUND)
            .verify();
    }
}
