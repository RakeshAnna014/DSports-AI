package com.dsports.shipment.application.usecase;

import com.dsports.shipment.application.command.CreateShipmentCommand;
import com.dsports.shipment.application.port.CourierGateway;
import com.dsports.shipment.application.port.CourierGateway.CourierGatewayResult;
import com.dsports.shipment.application.port.EventPublisher;
import com.dsports.shipment.application.port.OrderQueryService;
import com.dsports.shipment.application.port.ShipmentRepository;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShippingAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateShipmentUseCaseTest {

    private ShipmentRepository repository;
    private OrderQueryService orderQueryService;
    private CourierGateway courierGateway;
    private CreateShipmentUseCase useCase;

    private final UUID orderId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ShipmentRepository.class);
        orderQueryService = mock(OrderQueryService.class);
        courierGateway = mock(CourierGateway.class);
        useCase = new CreateShipmentUseCase(repository, orderQueryService, courierGateway, mock(EventPublisher.class));
        when(repository.findByOrderId(orderId)).thenReturn(Mono.empty());
        when(repository.save(any())).thenReturn(Mono.empty());
        when(courierGateway.createShipment(any())).thenReturn(Mono.just(new CourierGatewayResult(true, "Created", "TRK-123", null)));
    }

    @Test
    void addressesShipmentToTheOrdersSnapshotAddress() {
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.just(paidOrder()));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .assertNext(result -> {
                assertThat(result.trackingNumber()).isEqualTo("TRK-123");
                assertThat(result.street()).isEqualTo("1 MG Road, Flat 4");
                assertThat(result.city()).isEqualTo("Bengaluru");
                assertThat(result.zipCode()).isEqualTo("560001");
            })
            .verifyComplete();

        var saved = ArgumentCaptor.forClass(Shipment.class);
        verify(repository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getShippingAddress().street()).doesNotContain("N/A");
    }

    @Test
    void returnsExistingShipmentInsteadOfCreatingASecondOne() {
        var existing = Shipment.create(ShipmentId.generate(), orderId, userId,
            new ShippingAddress("1 MG Road", "Bengaluru", "KA", "560001", "IN"));
        when(repository.findByOrderId(orderId)).thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .assertNext(result -> assertThat(result.id()).isEqualTo(existing.getId().value()))
            .verifyComplete();
        verify(repository, never()).save(any());
        verify(orderQueryService, never()).findOrder(any());
    }

    @Test
    void refusesShipmentForUnpaidOrder() {
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.just(new OrderQueryService.OrderForShipment(
            orderId, userId, false, "1 MG Road", "Bengaluru", "KA", "560001", "IN")));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .expectErrorMatches(e -> e instanceof ShipmentDomainException sde
                && sde.getErrorCode() == ShipmentErrorCode.ORDER_NOT_CONFIRMED)
            .verify();
        verify(repository, never()).save(any());
    }

    @Test
    void refusesShipmentForUnknownOrder() {
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .expectErrorMatches(e -> e instanceof ShipmentDomainException sde
                && sde.getErrorCode() == ShipmentErrorCode.ORDER_NOT_FOUND)
            .verify();
    }

    @Test
    void rejectsIncompleteSnapshotAddress() {
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.just(new OrderQueryService.OrderForShipment(
            orderId, userId, true, "1 MG Road", "", "KA", "560001", "IN")));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .expectErrorMatches(e -> e instanceof ShipmentDomainException sde
                && sde.getErrorCode() == ShipmentErrorCode.INVALID_ADDRESS)
            .verify();
    }

    @Test
    void concurrentDuplicateResolvesToTheExistingShipment() {
        var existing = Shipment.create(ShipmentId.generate(), orderId, userId,
            new ShippingAddress("1 MG Road", "Bengaluru", "KA", "560001", "IN"));
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.just(paidOrder()));
        when(repository.findByOrderId(orderId)).thenReturn(Mono.empty(), Mono.just(existing));
        when(repository.save(any())).thenReturn(Mono.error(
            new ShipmentDomainException(ShipmentErrorCode.ORDER_ALREADY_SHIPPED, "exists")));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .assertNext(result -> assertThat(result.id()).isEqualTo(existing.getId().value()))
            .verifyComplete();
    }

    @Test
    void courierFailureMarksShipmentFailedWithoutLosingIt() {
        when(orderQueryService.findOrder(orderId)).thenReturn(Mono.just(paidOrder()));
        when(courierGateway.createShipment(any())).thenReturn(Mono.just(new CourierGatewayResult(false, "No courier", null, null)));

        StepVerifier.create(useCase.execute(new CreateShipmentCommand(orderId)))
            .assertNext(result -> assertThat(result.status()).isEqualTo("FAILED"))
            .verifyComplete();
    }

    private OrderQueryService.OrderForShipment paidOrder() {
        return new OrderQueryService.OrderForShipment(orderId, userId, true,
            "1 MG Road, Flat 4", "Bengaluru", "KA", "560001", "IN");
    }
}
