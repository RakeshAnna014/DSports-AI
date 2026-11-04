package com.dsports.shipment.infrastructure.gateway;

import com.dsports.shipment.domain.model.CourierProvider;
import com.dsports.shipment.domain.model.Shipment;
import com.dsports.shipment.domain.model.ShipmentId;
import com.dsports.shipment.domain.model.ShippingAddress;
import com.dsports.shipment.domain.model.TrackingNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockCourierGatewayTest {

    private MockCourierGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockCourierGateway();
    }

    @Test
    void shouldCreateShipment() {
        var shipment = Shipment.create(ShipmentId.generate(), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));

        StepVerifier.create(gateway.createShipment(shipment))
            .assertNext(result -> {
                assertTrue(result.success());
                assertNotNull(result.providerReference());
                assertNotNull(result.trackingData());
            })
            .verifyComplete();
    }

    @Test
    void shouldReturnTrackingNumberThatFitsTheShipmentsColumnAsProviderReference() {
        var shipment = Shipment.create(ShipmentId.generate(), UUID.randomUUID(), UUID.randomUUID(),
            new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India"));

        StepVerifier.create(gateway.createShipment(shipment))
            .assertNext(result -> {
                assertTrue(result.providerReference().startsWith("TRK-"));
                assertTrue(result.providerReference().length() <= 20,
                    "shipments.tracking_number is VARCHAR(20)");
            })
            .verifyComplete();
    }

    @Test
    void shouldTrackShipment() {
        var trackingNumber = TrackingNumber.generate();

        StepVerifier.create(gateway.trackShipment(trackingNumber))
            .assertNext(result -> {
                assertTrue(result.success());
                assertNotNull(result.message());
                assertNotNull(result.trackingData());
            })
            .verifyComplete();
    }
}
