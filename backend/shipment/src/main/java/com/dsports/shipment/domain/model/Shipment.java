package com.dsports.shipment.domain.model;

import com.dsports.shipment.domain.event.ShipmentCreatedEvent;
import com.dsports.shipment.domain.event.ShipmentDeliveredEvent;
import com.dsports.shipment.domain.event.ShipmentInTransitEvent;
import com.dsports.shipment.domain.event.ShipmentOutForDeliveryEvent;
import com.dsports.shipment.domain.event.ShipmentPickedUpEvent;
import com.dsports.shipment.domain.event.ShipmentReturnedEvent;
import com.dsports.shipment.domain.exception.ShipmentDomainException;
import com.dsports.shipment.domain.exception.ShipmentErrorCode;
import com.dsports.shared.domain.kernel.DomainEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Shipment {

    private final ShipmentId id;
    private final ShipmentNumber shipmentNumber;
    private TrackingNumber trackingNumber;
    private final UUID orderId;
    private final UUID userId;
    private CourierProvider courierProvider;
    private ShipmentStatus status;
    private final ShippingAddress shippingAddress;
    private Instant estimatedDeliveryDate;
    private Instant actualDeliveryDate;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant createdAt;
    private Instant updatedAt;
    private int version;
    private transient List<DomainEvent> domainEvents = new ArrayList<>();

    private Shipment(ShipmentId id, ShipmentNumber shipmentNumber, TrackingNumber trackingNumber,
                     UUID orderId, UUID userId, CourierProvider courierProvider, ShipmentStatus status,
                     ShippingAddress shippingAddress, Instant estimatedDeliveryDate, Instant actualDeliveryDate,
                     Instant shippedAt, Instant deliveredAt, Instant createdAt, Instant updatedAt, int version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.shipmentNumber = shipmentNumber;
        this.trackingNumber = trackingNumber;
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.courierProvider = courierProvider;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.shippingAddress = Objects.requireNonNull(shippingAddress, "shippingAddress must not be null");
        this.estimatedDeliveryDate = estimatedDeliveryDate;
        this.actualDeliveryDate = actualDeliveryDate;
        this.shippedAt = shippedAt;
        this.deliveredAt = deliveredAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Shipment create(ShipmentId id, UUID orderId, UUID userId, ShippingAddress shippingAddress) {
        Instant now = Instant.now();
        Shipment shipment = new Shipment(
            id,
            ShipmentNumber.generate(),
            null,
            orderId,
            userId,
            null,
            ShipmentStatus.CREATED,
            shippingAddress,
            null,
            null,
            null,
            null,
            now,
            now,
            0
        );
        shipment.domainEvents.add(new ShipmentCreatedEvent(id, orderId, userId));
        return shipment;
    }

    public static Shipment reconstitute(ShipmentId id, ShipmentNumber shipmentNumber, TrackingNumber trackingNumber,
                                        UUID orderId, UUID userId, CourierProvider courierProvider, ShipmentStatus status,
                                        ShippingAddress shippingAddress, Instant estimatedDeliveryDate, Instant actualDeliveryDate,
                                        Instant shippedAt, Instant deliveredAt, Instant createdAt, Instant updatedAt, int version) {
        return new Shipment(id, shipmentNumber, trackingNumber, orderId, userId, courierProvider, status,
            shippingAddress, estimatedDeliveryDate, actualDeliveryDate, shippedAt, deliveredAt, createdAt, updatedAt, version);
    }

    public void assignCourier(CourierProvider provider, TrackingNumber trackingNumber) {
        transitionTo(ShipmentStatus.READY_FOR_PICKUP);
        this.courierProvider = Objects.requireNonNull(provider, "courierProvider must not be null");
        this.trackingNumber = Objects.requireNonNull(trackingNumber, "trackingNumber must not be null");
        this.updatedAt = Instant.now();
    }

    public void pickup() {
        transitionTo(ShipmentStatus.PICKED_UP);
        this.updatedAt = Instant.now();
        this.domainEvents.add(new ShipmentPickedUpEvent(id, orderId, userId));
    }

    public void transit() {
        transitionTo(ShipmentStatus.IN_TRANSIT);
        this.updatedAt = Instant.now();
        this.shippedAt = Instant.now();
        this.domainEvents.add(new ShipmentInTransitEvent(id, orderId, userId));
    }

    public void outForDelivery() {
        transitionTo(ShipmentStatus.OUT_FOR_DELIVERY);
        this.updatedAt = Instant.now();
        this.domainEvents.add(new ShipmentOutForDeliveryEvent(id, orderId, userId));
    }

    public void deliver() {
        transitionTo(ShipmentStatus.DELIVERED);
        this.deliveredAt = Instant.now();
        this.actualDeliveryDate = Instant.now();
        this.updatedAt = Instant.now();
        this.domainEvents.add(new ShipmentDeliveredEvent(id, orderId, userId));
    }

    public void markFailed(String reason) {
        transitionTo(ShipmentStatus.FAILED);
        this.updatedAt = Instant.now();
    }

    public void returnShipment() {
        transitionTo(ShipmentStatus.RETURNED);
        this.updatedAt = Instant.now();
        this.domainEvents.add(new ShipmentReturnedEvent(id, orderId, userId));
    }

    private void transitionTo(ShipmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ShipmentDomainException(
                ShipmentErrorCode.INVALID_STATUS_TRANSITION,
                "Cannot transition from " + status + " to " + target
            );
        }
        this.status = target;
    }

    public ShipmentId getId() { return id; }
    public ShipmentNumber getShipmentNumber() { return shipmentNumber; }
    public TrackingNumber getTrackingNumber() { return trackingNumber; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public CourierProvider getCourierProvider() { return courierProvider; }
    public ShipmentStatus getStatus() { return status; }
    public ShippingAddress getShippingAddress() { return shippingAddress; }
    public Instant getEstimatedDeliveryDate() { return estimatedDeliveryDate; }
    public Instant getActualDeliveryDate() { return actualDeliveryDate; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getVersion() { return version; }

    public void setVersion(int version) {
        this.version = version;
    }

    public List<DomainEvent> getDomainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    public void clearDomainEvents() {
        this.domainEvents = new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Shipment shipment)) return false;
        return Objects.equals(id, shipment.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Shipment{" +
            "id=" + id +
            ", shipmentNumber=" + shipmentNumber +
            ", trackingNumber=" + trackingNumber +
            ", orderId=" + orderId +
            ", userId=" + userId +
            ", courierProvider=" + courierProvider +
            ", status=" + status +
            ", shippingAddress=" + shippingAddress +
            ", estimatedDeliveryDate=" + estimatedDeliveryDate +
            ", actualDeliveryDate=" + actualDeliveryDate +
            ", shippedAt=" + shippedAt +
            ", deliveredAt=" + deliveredAt +
            ", createdAt=" + createdAt +
            ", updatedAt=" + updatedAt +
            ", version=" + version +
            '}';
    }
}
