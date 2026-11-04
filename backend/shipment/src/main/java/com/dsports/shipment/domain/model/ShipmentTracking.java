package com.dsports.shipment.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ShipmentTracking {

    private final UUID trackingId;
    private final ShipmentId shipmentId;
    private final ShipmentStatus status;
    private final String location;
    private final String description;
    private final Instant eventTime;

    public ShipmentTracking(UUID trackingId, ShipmentId shipmentId, ShipmentStatus status,
                            String location, String description, Instant eventTime) {
        this.trackingId = Objects.requireNonNull(trackingId, "trackingId must not be null");
        this.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.location = location;
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.eventTime = Objects.requireNonNull(eventTime, "eventTime must not be null");
    }

    public UUID getTrackingId() { return trackingId; }
    public ShipmentId getShipmentId() { return shipmentId; }
    public ShipmentStatus getStatus() { return status; }
    public String getLocation() { return location; }
    public String getDescription() { return description; }
    public Instant getEventTime() { return eventTime; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShipmentTracking that)) return false;
        return Objects.equals(trackingId, that.trackingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(trackingId);
    }

    @Override
    public String toString() {
        return "ShipmentTracking{" +
            "trackingId=" + trackingId +
            ", shipmentId=" + shipmentId +
            ", status=" + status +
            ", location='" + location + '\'' +
            ", description='" + description + '\'' +
            ", eventTime=" + eventTime +
            '}';
    }
}
