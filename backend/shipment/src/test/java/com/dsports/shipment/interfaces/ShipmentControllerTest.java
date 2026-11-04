package com.dsports.shipment.interfaces;

import com.dsports.shipment.application.command.AssignCourierCommand;
import com.dsports.shipment.application.command.CreateShipmentCommand;
import com.dsports.shipment.application.command.UpdateShipmentStatusCommand;
import com.dsports.shipment.application.query.GetShipmentQuery;
import com.dsports.shipment.application.query.TrackShipmentQuery;
import com.dsports.shipment.application.result.ShipmentResult;
import com.dsports.shipment.application.usecase.AssignCourierUseCase;
import com.dsports.shipment.application.usecase.CreateShipmentUseCase;
import com.dsports.shipment.application.usecase.GetShipmentUseCase;
import com.dsports.shipment.application.usecase.TrackShipmentUseCase;
import com.dsports.shipment.application.usecase.UpdateShipmentStatusUseCase;
import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.ShipmentStatus;
import com.dsports.shipment.interfaces.ShipmentController.CreateShipmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShipmentControllerTest {

    private CreateShipmentUseCase createUseCase;
    private GetShipmentUseCase getUseCase;
    private TrackShipmentUseCase trackUseCase;
    private AssignCourierUseCase assignUseCase;
    private UpdateShipmentStatusUseCase updateUseCase;
    private ShipmentController controller;
    private UUID userId;
    private ShipmentResult sampleResult;

    @BeforeEach
    void setUp() {
        createUseCase = mock(CreateShipmentUseCase.class);
        getUseCase = mock(GetShipmentUseCase.class);
        trackUseCase = mock(TrackShipmentUseCase.class);
        assignUseCase = mock(AssignCourierUseCase.class);
        updateUseCase = mock(UpdateShipmentStatusUseCase.class);
        controller = new ShipmentController(createUseCase, getUseCase,
                org.mockito.Mockito.mock(com.dsports.shipment.application.usecase.GetShipmentByOrderUseCase.class),
                trackUseCase, assignUseCase, updateUseCase);
        userId = UUID.randomUUID();
        sampleResult = new ShipmentResult(
                UUID.randomUUID(), "SH-TEST", null,
                UUID.randomUUID(), userId, null,
                ShipmentStatus.CREATED.name(),
                "123 Main St", "Mumbai", "MH", "400001", "India",
                null, null, null, null,
                Instant.now(), Instant.now(), 0);
    }

    @Test
    void shouldCreateShipment() {
        var request = new CreateShipmentRequest(UUID.randomUUID());
        when(createUseCase.execute(any(CreateShipmentCommand.class))).thenReturn(Mono.just(sampleResult));

        StepVerifier.create(controller.createShipment(request))
                .expectNext(sampleResult)
                .verifyComplete();
    }

    @Test
    void shouldGetShipmentById() {
        when(getUseCase.execute(any(GetShipmentQuery.class))).thenReturn(Mono.just(sampleResult));
        var auth = userAuth(userId);

        StepVerifier.create(controller.getShipment(auth, sampleResult.id()))
                .expectNextMatches(r -> r.getStatusCode().is2xxSuccessful())
                .verifyComplete();
    }

    @Test
    void shouldTrackByNumber() {
        when(trackUseCase.execute(any(TrackShipmentQuery.class))).thenReturn(Mono.just(sampleResult));

        StepVerifier.create(controller.trackShipment(userAuth(userId), "TRK-TEST"))
                .expectNextMatches(r -> r.getStatusCode().is2xxSuccessful())
                .verifyComplete();
    }

    @Test
    void shouldReturnForbiddenForNonAdminAssignCourier() {
        var auth = userAuth(userId);

        StepVerifier.create(controller.assignCourier(auth, UUID.randomUUID(),
                        new ShipmentController.AssignCourierRequest(CourierProvider.MOCK)))
                .expectError()
                .verify();
    }

    @Test
    void shouldReturnForbiddenForNonAdminUpdateStatus() {
        var auth = userAuth(userId);

        StepVerifier.create(controller.updateStatus(auth, UUID.randomUUID(),
                        new ShipmentController.UpdateStatusRequest(ShipmentStatus.DELIVERED, null)))
                .expectError()
                .verify();
    }

    private Authentication userAuth(UUID uid) {
        return new Authentication() {
            @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
            @Override public Object getCredentials() { return null; }
            @Override public Object getDetails() { return null; }
            @Override public Object getPrincipal() { return uid.toString(); }
            @Override public boolean isAuthenticated() { return true; }
            @Override public void setAuthenticated(boolean isAuthenticated) {}
            @Override public String getName() { return uid.toString(); }
        };
    }
}
