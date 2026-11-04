package com.dsports.shipment.domain.model;

import com.dsports.shipment.domain.event.ShipmentCreatedEvent;
import com.dsports.shipment.domain.event.ShipmentDeliveredEvent;
import com.dsports.shipment.domain.event.ShipmentInTransitEvent;
import com.dsports.shipment.domain.event.ShipmentOutForDeliveryEvent;
import com.dsports.shipment.domain.event.ShipmentPickedUpEvent;
import com.dsports.shipment.domain.event.ShipmentReturnedEvent;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShipmentTest {

    private ShipmentId shipmentId;
    private UUID orderId;
    private UUID userId;
    private ShippingAddress address;
    private Shipment shipment;

    @BeforeEach
    void setUp() {
        shipmentId = ShipmentId.generate();
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        address = new ShippingAddress("123 Main St", "Mumbai", "MH", "400001", "India");
        shipment = Shipment.create(shipmentId, orderId, userId, address);
    }

    @Test
    void shouldCreateShipment() {
        assertEquals(shipmentId, shipment.getId());
        assertEquals(orderId, shipment.getOrderId());
        assertEquals(userId, shipment.getUserId());
        assertEquals(ShipmentStatus.CREATED, shipment.getStatus());
        assertNotNull(shipment.getShipmentNumber());
        assertNull(shipment.getTrackingNumber());
        assertEquals(address, shipment.getShippingAddress());
        assertFalse(shipment.getDomainEvents().isEmpty());
        assertInstanceOf(ShipmentCreatedEvent.class, shipment.getDomainEvents().get(0));
    }

    @Test
    void shouldAssignCourier() {
        var trackingNumber = TrackingNumber.generate();
        shipment.assignCourier(CourierProvider.MOCK, trackingNumber);

        assertEquals(CourierProvider.MOCK, shipment.getCourierProvider());
        assertEquals(trackingNumber, shipment.getTrackingNumber());
        assertEquals(ShipmentStatus.READY_FOR_PICKUP, shipment.getStatus());
    }

    @Test
    void shouldNotAssignCourierInInvalidState() {
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());
        shipment.pickup();
        assertThrows(ShipmentDomainException.class,
                () -> shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate()));
    }

    @Test
    void shouldProgressThroughStates() {
        var trackingNumber = TrackingNumber.generate();
        shipment.assignCourier(CourierProvider.MOCK, trackingNumber);
        shipment.pickup();
        assertEquals(ShipmentStatus.PICKED_UP, shipment.getStatus());
        assertInstanceOf(ShipmentPickedUpEvent.class, shipment.getDomainEvents().get(1));

        shipment.transit();
        assertEquals(ShipmentStatus.IN_TRANSIT, shipment.getStatus());
        assertInstanceOf(ShipmentInTransitEvent.class, shipment.getDomainEvents().get(2));

        shipment.outForDelivery();
        assertEquals(ShipmentStatus.OUT_FOR_DELIVERY, shipment.getStatus());
        assertInstanceOf(ShipmentOutForDeliveryEvent.class, shipment.getDomainEvents().get(3));

        shipment.deliver();
        assertEquals(ShipmentStatus.DELIVERED, shipment.getStatus());
        assertNotNull(shipment.getDeliveredAt());
        assertInstanceOf(ShipmentDeliveredEvent.class, shipment.getDomainEvents().get(4));
    }

    @Test
    void shouldMarkFailed() {
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());
        shipment.markFailed("Package damaged");
        assertEquals(ShipmentStatus.FAILED, shipment.getStatus());
    }

    @Test
    void shouldReturnShipment() {
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());
        shipment.pickup();
        shipment.transit();
        shipment.returnShipment();
        assertEquals(ShipmentStatus.RETURNED, shipment.getStatus());
        assertTrue(shipment.getStatus().isTerminal());
        assertInstanceOf(ShipmentReturnedEvent.class,
                shipment.getDomainEvents().get(shipment.getDomainEvents().size() - 1));
    }

    @Test
    void shouldNotTransitionFromTerminalState() {
        shipment.assignCourier(CourierProvider.MOCK, TrackingNumber.generate());
        shipment.pickup();
        shipment.transit();
        shipment.outForDelivery();
        shipment.deliver();
        assertTrue(shipment.getStatus().isTerminal());
        assertThrows(ShipmentDomainException.class, () -> shipment.transit());
    }

    @Test
    void shouldReconstitute() {
        var trackingNumber = TrackingNumber.generate();
        var shipmentNumber = ShipmentNumber.generate();
        var now = java.time.Instant.now();
        var reconstituted = Shipment.reconstitute(
                shipmentId, shipmentNumber, trackingNumber,
                orderId, userId, CourierProvider.MOCK,
                ShipmentStatus.IN_TRANSIT, address,
                null, null, now, null, now, now, 1);

        assertEquals(shipmentId, reconstituted.getId());
        assertEquals(ShipmentStatus.IN_TRANSIT, reconstituted.getStatus());
        assertEquals(trackingNumber.value(), reconstituted.getTrackingNumber().value());
        assertEquals(1, reconstituted.getVersion());
    }

    @Test
    void shouldRejectNullOrderId() {
        assertThrows(NullPointerException.class,
                () -> Shipment.create(shipmentId, null, userId, address));
    }

    @Test
    void shouldRejectNullUserId() {
        assertThrows(NullPointerException.class,
                () -> Shipment.create(shipmentId, orderId, null, address));
    }
}
